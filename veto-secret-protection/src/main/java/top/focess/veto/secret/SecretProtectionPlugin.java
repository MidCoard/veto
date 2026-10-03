package top.focess.veto.secret;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolSecurity;
import top.focess.veto.api.credentials.VaultAccess;
import top.focess.veto.api.event.*;
import top.focess.veto.api.llm.LocalModelCompletion;
import top.focess.veto.api.llm.PromptRenderer;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.contract.*;
import top.focess.veto.api.plugin.contribution.*;
import top.focess.veto.secret.detection.MdcSecretDetectionModel;
import top.focess.veto.secret.detection.SlmSecretDetector;
import top.focess.veto.secret.references.SecretCandidateStore;

/**
 * Self-contained provider using the same lifecycle and registration contract as installed plugins.
 */
public final class SecretProtectionPlugin extends VetoPlugin {
    private static final @NonNull ObjectMapper RESULTS = new ObjectMapper();

    private final @NonNull SecretCandidateStore candidates;

    private final @NonNull VaultAccess vault;

    private ScheduledExecutorService expiry;

    /** Constructs the plugin with host-granted import access and its complete contributions. */
    public SecretProtectionPlugin(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        if (!configuration.values().isEmpty())
            throw new IllegalArgumentException("Unsupported configuration");
        candidates = new SecretCandidateStore();
        vault =
                context.service(VaultAccess.class)
                        .orElse(
                                arguments -> {
                                    throw new IllegalStateException("Vault access is unavailable");
                                });
        var localModel = context.service(LocalModelCompletion.class).orElse(null);
        var prompts = context.service(PromptRenderer.class).orElse(null);
        var detector =
                new SlmSecretDetector(
                        localModel == null || prompts == null
                                ? null
                                : new MdcSecretDetectionModel(localModel, prompts));
        candidates.detector(detector);
        context.register(StandardContributionPoints.FRONTEND, "frontend", new SecretFrontend());
        context.register(
                StandardContributionPoints.OBSERVATION,
                "observation-mask",
                new SecretObservation(detector));
        context.register(
                StandardContributionPoints.LISTENERS,
                "lifecycle",
                new SecretLifecycle(candidates, detector));
        context.register(
                StandardContributionPoints.TOOLS,
                "import_detected_credential",
                new ImportCredentialTool());
    }

    @Override
    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("top.focess.secret-protection", "1.0.100");
    }

    @Override
    public @NonNull String displayName() {
        return "Secret Protection";
    }

    @Override
    public @NonNull Set<@NonNull String> historicalIds() {
        return Set.of("org.veto.secret-protection");
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
                    """
                    Import a session-registered SECRET_REF into the owner's encrypted vault after approval.\
                    """,
            behavior =
                    """
                    Resolves the referenced secret candidate captured earlier in this session, checks the \
                    approved tool invocation, and writes the credential into the owner's encrypted vault exactly \
                    once. The plaintext secret never passes through the model or the tool arguments; only the \
                    opaque reference, target service, and a human label are supplied.\
                    """,
            whenToUse =
                    """
                    Use it when the user has explicitly approved persisting a detected credential that was masked \
                    as a SECRET_REF during this session, so it can be reused later without re-exposing the \
                    plaintext.\
                    """,
            whenNotToUse =
                    """
                    Do not use it to store a secret the user pasted in plaintext, to import a reference the user \
                    has not approved, or as a general key-value store. Leave unapproved candidates masked.\
                    """,
            resultContract =
                    """
                    Success returns JSON with `credential_ref` (the stable vault handle), `service`, `label`, and \
                    `status` (`created`). Failures return a plaintext diagnostic without echoing the secret \
                    value.\
                    """,
            errorsAndEdgeCases =
                    """
                    Import is idempotent per reference: re-importing the same SECRET_REF returns the existing \
                    vault handle rather than duplicating it. An unknown, expired, or cross-session reference is \
                    refused, and unavailable vault access surfaces as a failure. Cancellation before the \
                    irreversible vault write aborts the import.\
                    """,
            security =
                    """
                    Crosses the host trust boundary and writes to the encrypted vault; every call requires \
                    explicit approval. Never accepts plaintext secret material, only an opaque session-scoped \
                    reference.\
                    """,
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
    final class ImportCredentialTool extends NativeTool<ImportCredentialArgs> {
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
            return ImportCredentialArgs.class;
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

    private final class SecretFrontend implements FrontendContribution {
        @Override
        public @NonNull String module() {
            return frontendModule();
        }

        @Override
        public @NonNull JsonValue handle(
                Scope.@NonNull AgentScope scope,
                @NonNull String action,
                JsonValue.@NonNull ObjectValue arguments)
                throws PluginFailure {
            if (!action.equals("show")
                    || !(arguments.values().get("reference") instanceof JsonValue.StringValue ref))
                throw new PluginFailure(PluginFailure.Code.INVALID_ARGUMENTS);
            return candidates
                    .reveal(scope, ref.value())
                    .<JsonValue>map(JsonValue.StringValue::new)
                    .orElse(JsonValue.NullValue.INSTANCE);
        }
    }

    private static final class SecretObservation implements ObservationMiddleware {
        private final @NonNull SlmSecretDetector detector;

        private SecretObservation(@NonNull SlmSecretDetector detector) {
            this.detector = detector;
        }

        @Override
        public @NonNull String transform(
                @NonNull String observation, @NonNull Cancellation cancellation) {
            return detector.mask(observation);
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

    private @NonNull ImportCredentialResult importCredential(@NonNull ImportCredentialArgs args) {
        var authorized =
                vault.open(
                        Map.of(
                                "secret_ref", args.secret_ref(),
                                "service", args.service(),
                                "label", args.label()));
        // Re-check after the (potentially blocking) host authorization so a caller that cancelled
        // while awaiting approval does not reach the irreversible vault write.
        if (Thread.currentThread().isInterrupted())
            throw new IllegalStateException("Credential import cancelled");
        var receipt =
                candidates.importOnce(
                        authorized.scope(),
                        args.secret_ref(),
                        args.service(),
                        args.label(),
                        authorized);
        return new ImportCredentialResult(
                receipt.credentialRef(), receipt.service(), receipt.label(), "created");
    }

    @Override
    public void start() {
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
    public void close() {
        var executor = expiry;
        if (executor != null) executor.shutdownNow();
        candidates.clear();
    }

    /**
     * Scopes secret-candidate availability to the owner, session, and agent transitions the host
     * broadcasts. A closed scope makes its captured references unrecoverable, so capture fails
     * closed until the owner is opened again.
     */
    public static final class SecretLifecycle implements Listener {
        private final @NonNull SecretCandidateStore candidates;
        private final @NonNull SlmSecretDetector detector;

        /** Creates the listener over the candidate store it scopes. */
        public SecretLifecycle(
                @NonNull SecretCandidateStore candidates, @NonNull SlmSecretDetector detector) {
            this.candidates = candidates;
            this.detector = detector;
        }

        /** Protects text before the host commits it, preserving captured reference markers. */
        @EventHandler
        public void onTextCommit(@NonNull BeforeTextCommitEvent event) {
            var scope = event.scope();
            switch (event.phase()) {
                case INPUT ->
                        event.setText(
                                candidates.capture(scope, event.sourceId(), event.text()).text());
                case FILE_CAPTURE ->
                        event.setText(
                                candidates
                                        .captureFile(scope, event.sourceId(), event.text())
                                        .text());
                case FILE_OBSERVATION -> {
                    var output = new StringBuilder();
                    for (var segment : candidates.referenceSegments(scope, event.text()))
                        output.append(
                                segment.reference()
                                        ? segment.text()
                                        : detector.mask(segment.text()));
                    event.setText(output.toString());
                }
            }
        }

        @EventHandler
        public void onUserAuthenticated(@NonNull UserAuthenticatedEvent event) {
            candidates.openOwner(event.scope().owner());
        }

        @EventHandler
        public void onUserLogout(@NonNull UserLogoutEvent event) {
            candidates.closeOwner(event.scope().owner());
        }

        @EventHandler
        public void onSessionDeleted(@NonNull SessionDeletedEvent event) {
            candidates.retireSession(event.scope().owner(), event.scope().session());
        }

        @EventHandler
        public void onAgentTerminated(@NonNull AgentTerminatedEvent event) {
            candidates.discardAgent(event.scope());
        }
    }
}
