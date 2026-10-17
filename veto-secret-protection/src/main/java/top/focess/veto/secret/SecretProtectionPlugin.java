package top.focess.veto.secret;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.credentials.VaultAccess;
import top.focess.veto.api.event.*;
import top.focess.veto.api.llm.LocalModelCompletion;
import top.focess.veto.api.llm.PromptRenderer;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.contract.*;
import top.focess.veto.secret.detection.MdcSecretDetectionModel;
import top.focess.veto.secret.detection.SlmSecretDetector;
import top.focess.veto.secret.references.SecretCandidateStore;

/**
 * Self-contained provider using the same lifecycle and registration contract as installed plugins.
 */
public final class SecretProtectionPlugin extends VetoPlugin {

    private final @NonNull SecretCandidateStore candidates;

    private ScheduledExecutorService expiry;

    /** Constructs the plugin with host-granted import access and its complete contributions. */
    public SecretProtectionPlugin(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        if (!configuration.values().isEmpty())
            throw new IllegalArgumentException("Unsupported configuration");
        candidates = new SecretCandidateStore();
        var vault =
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
        context.register(
                StandardContributionPoints.FRONTEND,
                "frontend",
                new SecretCandidateFrontend(candidates));
        context.register(
                StandardContributionPoints.OBSERVATION,
                "observation-mask",
                (observation, cancellation) -> detector.mask(observation));
        context.register(
                StandardContributionPoints.LISTENERS,
                "lifecycle",
                new SecretProtectionListener(candidates, detector));
        context.register(
                StandardContributionPoints.TOOLS,
                "import_detected_credential",
                new ImportCredentialTool(candidates, vault));
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
}
