package top.focess.veto.secret;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolSecurity;
import top.focess.veto.api.credentials.CredentialImportAccess;
import top.focess.veto.api.llm.LocalModelCompletion;
import top.focess.veto.api.llm.PromptRenderer;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.AbstractVetoPlugin;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.contract.*;
import top.focess.veto.api.plugin.contract.FileObservation;
import top.focess.veto.api.plugin.contract.FileProtection;
import top.focess.veto.api.plugin.contract.FrontendContribution;
import top.focess.veto.api.plugin.contract.InputProtection;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.ObservationMiddleware;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.SessionLifecycle;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contract.TextProtection;
import top.focess.veto.api.plugin.contribution.*;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.secret.detection.MdcSecretDetectionModel;
import top.focess.veto.secret.detection.SlmSecretDetector;
import top.focess.veto.secret.references.SecretCandidateStore;

/**
 * Self-contained provider using the same lifecycle and registration contract as installed plugins.
 */
public final class SecretProtectionPlugin extends AbstractVetoPlugin {
    private static final @NonNull ObjectMapper RESULTS = new ObjectMapper();

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
        var localModel = context.service(LocalModelCompletion.class).orElse(null);
        var prompts = context.service(PromptRenderer.class).orElse(null);
        var detector =
                new SlmSecretDetector(
                        localModel == null || prompts == null
                                ? null
                                : new MdcSecretDetectionModel(localModel, prompts));
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
                                StandardContributionPoints.NATIVE_TOOLS,
                                "import_detected_credential",
                                new ImportCredentialTool())));
    }

    /**
     * In-process plugin tool executed through the host's internal tool state exactly like a core
     * native tool: the host reflects {@link ImportCredentialArgs} into the input schema, validates
     * and deserializes the call, and invokes {@link #execute}. The PRIVILEGED effect keeps every
     * call behind approval-level Gateway screening.
     */
    @ToolSecurity(
            capability = ToolCapability.PRIVILEGED,
            defaultDanger = Danger.DANGEROUS,
            requiresSemanticScreening = true)
    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description =
                    "Import a session-registered SECRET_REF into the owner's encrypted vault after"
                            + " approval.",
            behavior =
                    """
                    Resolves the referenced secret candidate captured earlier in this session, asks \
                    the host to authorize the import, and writes the credential into the owner's \
                    encrypted vault exactly once. The plaintext secret never passes through the \
                    model or the tool arguments; only the opaque reference, target service, and a \
                    human label are supplied.""",
            whenToUse =
                    """
                    Use it when the user has explicitly approved persisting a detected credential \
                    that was masked as a SECRET_REF during this session, so it can be reused later \
                    without re-exposing the plaintext.""",
            whenNotToUse =
                    """
                    Do not use it to store a secret the user pasted in plaintext, to import a \
                    reference the user has not approved, or as a general key-value store. Leave \
                    unapproved candidates masked.""",
            resultContract =
                    """
                    Success returns JSON with `credential_ref` (the stable vault handle), `service`, \
                    `label`, and `status` (`created`). Failures return a plaintext diagnostic \
                    without echoing the secret value.""",
            errorsAndEdgeCases =
                    """
                    Import is idempotent per reference: re-importing the same SECRET_REF returns the \
                    existing vault handle rather than duplicating it. An unknown, expired, or \
                    cross-session reference is refused, and a missing import host surfaces as a \
                    failure. Cancellation before the irreversible vault write aborts the import.""",
            security =
                    "Crosses the host trust boundary and writes to the encrypted vault; every call requires explicit approval. Never accepts plaintext secret material, only an opaque session-scoped reference.",
            examples = {
                "{\"secret_ref\":\"SECRET_REF_1\",\"service\":\"github\",\"label\":\"ci-token\"}",
                "{\"secret_ref\":\"SECRET_REF_2\",\"service\":\"aws\",\"label\":\"deploy-key\"}",
                "{\"secret_ref\":\"SECRET_REF_3\",\"service\":\"github\",\"label\":\"webhook-secret\"}"
            },
            returnExamples = {
                "{\"credential_ref\":\"cred_01HXX\",\"service\":\"github\",\"label\":\"ci-token\",\"status\":\"created\"}",
                "{\"credential_ref\":\"cred_01HXY\",\"service\":\"aws\",\"label\":\"deploy-key\",\"status\":\"created\"}",
                "{\"credential_ref\":\"cred_01HXZ\",\"service\":\"github\",\"label\":\"webhook-secret\",\"status\":\"created\"}"
            })
    final class ImportCredentialTool implements CapabilityTool<ImportCredentialArgs> {
        @Override
        public @NonNull ToolCapability getCapability() {
            return ToolCapability.PRIVILEGED;
        }

        @Override
        public @NonNull String getName() {
            return "import_detected_credential";
        }

        @Override
        public @NonNull Class<ImportCredentialArgs> getArgsClass() {
            return ToolDocs.nonNullClass(ImportCredentialArgs.class);
        }

        @Override
        public @NonNull String execute(@NonNull ImportCredentialArgs args) throws Exception {
            return RESULTS.writeValueAsString(importCredential(args));
        }
    }

    /** Tool arguments; the host reflects this record into the input schema. */
    record ImportCredentialArgs(
            @NonNull @Doc("Opaque SECRET_REF captured earlier in this session; never plaintext.")
                    String secret_ref,
            @NonNull @Doc("Target service the credential belongs to, e.g. github or aws.")
                    String service,
            @NonNull @Doc("Human-readable label stored alongside the vault entry.") String label) {}

    /** Tool result; the host serializes this record back to JSON. */
    record ImportCredentialResult(
            @NonNull String credential_ref,
            @NonNull String service,
            @NonNull String label,
            @NonNull String status) {}

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

    private @NonNull ImportCredentialResult importCredential(@NonNull ImportCredentialArgs args) {
        var authorized = importer.authorize(args.secret_ref(), args.service(), args.label());
        // Re-check after the (potentially blocking) host authorization so a caller that cancelled
        // while awaiting approval does not reach the irreversible vault write.
        if (Thread.currentThread().isInterrupted())
            throw new IllegalStateException("Credential import cancelled");
        var receipt =
                candidates.importOnce(
                        new SecretCandidateStore.Scope(
                                authorized.ownerId(), authorized.sessionId(), authorized.agentId()),
                        args.secret_ref(),
                        args.service(),
                        args.label(),
                        authorized.writer());
        return new ImportCredentialResult(
                receipt.credentialRef(), receipt.service(), receipt.label(), "created");
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
