package top.focess.veto.secret;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.plugin.api.*;
import top.focess.veto.plugin.contract.*;
import top.focess.veto.plugin.contract.JsonValue;
import top.focess.veto.plugin.contribution.*;
import top.focess.veto.secret.api.CredentialImportAccess;
import top.focess.veto.secret.api.SecretDetectionModel;
import top.focess.veto.secret.detection.SlmSecretDetector;
import top.focess.veto.secret.references.SecretCandidateStore;

/**
 * Self-contained provider using the same lifecycle and registration contract as installed plugins.
 */
public final class SecretProtectionPlugin extends AbstractVetoPlugin {
    private final @NonNull SecretCandidateStore candidates;

    private @NonNull CredentialImportAccess importer;

    private @Nullable ScheduledExecutorService expiry;

    public SecretProtectionPlugin() {
        this(new SecretCandidateStore());
    }

    public SecretProtectionPlugin(
            @NonNull SecretCandidateStore candidates, @NonNull CredentialImportAccess importer) {
        this.candidates = candidates;
        this.importer = importer;
    }

    public SecretProtectionPlugin(@NonNull SecretCandidateStore candidates) {
        this(
                candidates,
                (reference, service, label) -> {
                    throw new IllegalStateException("Import host is unavailable");
                });
    }

    private static JsonValue.@NonNull ObjectValue schema(boolean input) {
        var properties = new HashMap<String, JsonValue>();
        for (String name :
                input
                        ? List.of("secret_ref", "service", "label")
                        : List.of("credential_ref", "service", "label", "status"))
            properties.put(
                    name,
                    new JsonValue.ObjectValue(Map.of("type", new JsonValue.StringValue("string"))));
        return new JsonValue.ObjectValue(
                Map.of(
                        "type",
                        new JsonValue.StringValue("object"),
                        "properties",
                        new JsonValue.ObjectValue(properties),
                        "required",
                        new JsonValue.ArrayValue(
                                properties.keySet().stream()
                                        .map(n -> (JsonValue) new JsonValue.StringValue(n))
                                        .toList()),
                        "additionalProperties",
                        new JsonValue.BooleanValue(false)));
    }

    private static SecretCandidateStore.@NonNull Scope scope(TextProtection.@NonNull Scope value) {
        return new SecretCandidateStore.Scope(value.ownerId(), value.sessionId(), value.agentId());
    }

    @Override
    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("top.focess.secret-protection", "1.0.100");
    }

    @Override
    @SuppressWarnings(
            "nullness") // Checker Framework treats this cross-module class literal as nullable.
    protected @NonNull PluginContributions onInitialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        if (!configuration.values().isEmpty())
            throw new IllegalArgumentException("Unsupported configuration");
        importer = context.service(CredentialImportAccess.class).orElse(importer);
        var detector =
                new SlmSecretDetector(context.service(SecretDetectionModel.class).orElse(null));
        candidates.detector(detector);
        return new PluginContributions(
                List.of(
                        Contribution.of(
                                StandardContributionPoints.FRONTEND,
                                "frontend",
                                new FrontendContribution(frontendModule(), this::frontendAction)),
                        Contribution.of(
                                StandardContributionPoints.INPUT_PROTECTION,
                                "input",
                                (InputProtection)
                                        (scope, source, text) ->
                                                candidates
                                                        .capture(scope(scope), source, text)
                                                        .text()),
                        Contribution.of(
                                StandardContributionPoints.FILE_OBSERVATION,
                                "observation",
                                (FileObservation)
                                        (scope, source, text) -> {
                                            var output = new StringBuilder();
                                            for (var segment :
                                                    candidates.referenceSegments(
                                                            scope(scope), text))
                                                output.append(
                                                        segment.reference()
                                                                ? segment.text()
                                                                : detector.mask(segment.text()));
                                            return output.toString();
                                        }),
                        Contribution.of(
                                StandardContributionPoints.FILE_PROTECTION,
                                "file",
                                (FileProtection)
                                        (scope, source, text) ->
                                                candidates
                                                        .captureFile(scope(scope), source, text)
                                                        .text()),
                        Contribution.of(
                                StandardContributionPoints.OBSERVATION,
                                "observation-mask",
                                (ObservationMiddleware)
                                        (observation, cancellation) -> detector.mask(observation)),
                        Contribution.of(
                                StandardContributionPoints.SESSION_LIFECYCLE,
                                "lifecycle",
                                new SessionLifecycle() {
                                    @Override
                                    public void onOwnerOpen(@NonNull String ownerId) {
                                        candidates.openOwner(ownerId);
                                    }

                                    @Override
                                    public void onOwnerClosed(@NonNull String ownerId) {
                                        candidates.closeOwner(ownerId);
                                    }

                                    @Override
                                    public void onSessionClosed(
                                            @NonNull String ownerId, @NonNull String sessionId) {
                                        candidates.retireSession(ownerId, sessionId);
                                    }

                                    @Override
                                    public void onAgentTerminated(
                                            @NonNull String ownerId,
                                            @NonNull String sessionId,
                                            @NonNull String agentId) {
                                        candidates.discardAgent(
                                                new SecretCandidateStore.Scope(
                                                        ownerId, sessionId, agentId));
                                    }
                                }),
                        Contribution.of(
                                StandardContributionPoints.TOOLS,
                                "import_detected_credential",
                                new ImportDetectedCredentialTool())));
    }

    /** The import tool as a direct {@link Tool} implementation. */
    private final class ImportDetectedCredentialTool implements Tool {
        @Override
        public @NonNull String description() {
            return "Import a registered SECRET_REF from this session into the owner's encrypted"
                    + " vault after approval. Use secret_ref, service (github), and label. Never"
                    + " provide plaintext.";
        }

        @Override
        public JsonValue.@NonNull ObjectValue inputSchema() {
            return schema(true);
        }

        @Override
        public JsonValue.@NonNull ObjectValue outputSchema() {
            return schema(false);
        }

        @Override
        public Tool.@NonNull Effect effect() {
            return Tool.Effect.PRIVILEGED;
        }

        @Override
        public @NonNull Set<@NonNull ContributionId> categories() {
            return Set.of();
        }

        @Override
        public @NonNull JsonValue invoke(
                JsonValue.@NonNull ObjectValue arguments, @NonNull Cancellation cancellation) {
            return importCredential(arguments, cancellation);
        }
    }

    private static @NonNull String frontendModule() {
        try (var stream =
                SecretProtectionPlugin.class.getResourceAsStream(
                        "/top/focess/veto/secret/frontend.mjs")) {
            if (stream == null) throw new IllegalStateException("Missing frontend module");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load frontend module", failure);
        }
    }

    private @NonNull JsonValue frontendAction(
            FrontendContribution.@NonNull Scope scope,
            @NonNull String action,
            JsonValue.@NonNull ObjectValue arguments)
            throws PluginFailure {
        if (!action.equals("show")
                || !(arguments.values().get("reference") instanceof JsonValue.StringValue ref))
            throw new PluginFailure(PluginFailure.Code.INVALID_ARGUMENTS);
        return candidates
                .reveal(
                        new SecretCandidateStore.Scope(
                                scope.ownerId(), scope.sessionId(), scope.agentId()),
                        ref.value())
                .<JsonValue>map(JsonValue.StringValue::new)
                .orElse(JsonValue.NullValue.INSTANCE);
    }

    private @NonNull JsonValue importCredential(
            JsonValue.@NonNull ObjectValue arguments, @NonNull Cancellation cancellation) {
        String reference = string(arguments, "secret_ref"),
                service = string(arguments, "service"),
                label = string(arguments, "label");
        var authorized = importer.authorize(reference, service, label);
        var receipt =
                candidates.importOnce(
                        new SecretCandidateStore.Scope(
                                authorized.ownerId(), authorized.sessionId(), authorized.agentId()),
                        reference,
                        service,
                        label,
                        authorized.writer());
        return new JsonValue.ObjectValue(
                Map.of(
                        "credential_ref",
                        new JsonValue.StringValue(receipt.credentialRef()),
                        "service",
                        new JsonValue.StringValue(receipt.service()),
                        "label",
                        new JsonValue.StringValue(receipt.label()),
                        "status",
                        new JsonValue.StringValue("created")));
    }

    private static @NonNull String string(
            JsonValue.@NonNull ObjectValue arguments, @NonNull String key) {
        if (arguments.values().get(key) instanceof JsonValue.StringValue value)
            return value.value();
        throw new IllegalArgumentException("Invalid credential import arguments");
    }

    @Override
    protected void onStart() {
        var executor =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform()
                                .daemon(true)
                                .name("veto-secret-protection-expiry")
                                .factory());
        executor.scheduleAtFixedRate(
                () -> {
                    try {
                        candidates.expire();
                    } catch (RuntimeException ignored) {
                        // A failed pass must not cancel future expiry runs.
                    }
                },
                60,
                60,
                TimeUnit.SECONDS);
        expiry = executor;
    }

    @Override
    protected void onClose() {
        var executor = expiry;
        if (executor != null) executor.shutdownNow();
        candidates.clear();
    }
}
