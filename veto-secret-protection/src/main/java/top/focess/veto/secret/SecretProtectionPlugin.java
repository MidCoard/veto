package top.focess.veto.secret;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.extension.*;
import top.focess.veto.extension.contract.*;
import top.focess.veto.extension.contract.JsonValue;
import top.focess.veto.plugin.api.*;
import top.focess.veto.secret.references.SecretCandidateStore;

/** Built-in provider using the same lifecycle and registration contract as installed plugins. */
public final class SecretProtectionPlugin extends AbstractVetoPlugin {
    @SuppressWarnings(
            "nullness") // Checker Framework treats this cross-module class literal as nullable.
    public static final @NonNull ExtensionPoint<SecretCandidateStore> CANDIDATES =
            new ExtensionPoint<>(
                    new ExtensionId("veto:secret-candidates"),
                    1,
                    SecretCandidateStore.class,
                    ExtensionPoint.Cardinality.MULTIPLE);

    private final @NonNull SecretCandidateStore candidates;

    private final top.focess.veto.secret.api.@NonNull CredentialImportAccess importer;

    public SecretProtectionPlugin(
            @NonNull SecretCandidateStore candidates,
            top.focess.veto.secret.api.@NonNull CredentialImportAccess importer) {
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
        var properties = new java.util.HashMap<String, JsonValue>();
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
    protected @NonNull PluginContributions onInitialize(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        if (!configuration.values().isEmpty())
            throw new IllegalArgumentException("Unsupported configuration");
        return new PluginContributions(
                List.of(
                        ExtensionContribution.of(CANDIDATES, "candidates", candidates),
                        ExtensionContribution.of(
                                StandardExtensionPoints.FRONTEND,
                                "frontend",
                                new FrontendExtension(frontendModule(), this::frontendAction)),
                        ExtensionContribution.of(
                                StandardExtensionPoints.INPUT_PROTECTION,
                                "input",
                                (scope, source, text) ->
                                        candidates.capture(scope(scope), source, text).text()),
                        ExtensionContribution.of(
                                StandardExtensionPoints.FILE_OBSERVATION,
                                "observation",
                                (scope, source, text) -> {
                                    var output = new StringBuilder();
                                    for (var segment :
                                            candidates.referenceSegments(scope(scope), text))
                                        output.append(
                                                segment.reference()
                                                        ? segment.text()
                                                        : top.focess.veto.secret.detection
                                                                .SecretMasker.mask(segment.text()));
                                    return output.toString();
                                }),
                        ExtensionContribution.of(
                                StandardExtensionPoints.FILE_PROTECTION,
                                "file",
                                (scope, source, text) ->
                                        candidates.captureFile(scope(scope), source, text).text()),
                        ExtensionContribution.of(
                                StandardExtensionPoints.TOOLS,
                                "import_detected_credential",
                                new ToolContribution(
                                        "Import a registered SECRET_REF from this session into the"
                                                + " owner's encrypted vault after approval. Use"
                                                + " secret_ref, service (github), and label. Never"
                                                + " provide plaintext.",
                                        schema(true),
                                        schema(false),
                                        ToolContribution.Effect.CREDENTIAL_IMPORT,
                                        Set.of(),
                                        this::importCredential))));
    }

    private static @NonNull String frontendModule() {
        try (var stream =
                SecretProtectionPlugin.class.getResourceAsStream(
                        "/top/focess/veto/secret/frontend.mjs")) {
            if (stream == null) throw new IllegalStateException("Missing frontend module");
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot load frontend module", failure);
        }
    }

    private @NonNull JsonValue frontendAction(
            FrontendExtension.@NonNull Scope scope,
            @NonNull String action,
            JsonValue.@NonNull ObjectValue arguments)
            throws ExtensionFailure {
        if (!action.equals("show")
                || !(arguments.values().get("reference") instanceof JsonValue.StringValue ref))
            throw new ExtensionFailure(ExtensionFailure.Code.INVALID_ARGUMENTS);
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
                        authorized.scope(), reference, service, label, authorized.writer());
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
    protected void onStart() {}

    @Override
    protected void onClose() {
        candidates.clear();
    }
}
