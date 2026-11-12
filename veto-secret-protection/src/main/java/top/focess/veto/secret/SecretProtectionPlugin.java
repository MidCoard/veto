package top.focess.veto.secret;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.credentials.VaultAccess;
import top.focess.veto.api.event.*;
import top.focess.veto.api.plugin.*;
import top.focess.veto.api.plugin.contract.*;
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
        if (!Set.of("modelPath").containsAll(configuration.values().keySet()))
            throw new IllegalArgumentException("Unsupported configuration");
        var configuredPath = configuration.values().get("modelPath");
        String modelPath = SecretAgent.DEFAULT_MODEL_PATH;
        if (configuredPath != null) {
            if (!(configuredPath instanceof JsonValue.StringValue path) || path.value().isBlank())
                throw new IllegalArgumentException("modelPath must be a nonblank local model path");
            modelPath = path.value();
        }
        candidates = new SecretCandidateStore();
        var vault =
                context.service(VaultAccess.class)
                        .orElse(
                                arguments -> {
                                    throw new IllegalStateException("Vault access is unavailable");
                                });
        var agent = new SecretAgent(context, candidates, modelPath);
        context.register(
                StandardContributionPoints.FRONTEND,
                "frontend",
                new SecretCandidateFrontend(candidates));
        context.register(
                StandardContributionPoints.LISTENERS,
                "lifecycle",
                new SecretProtectionListener(candidates, agent));
        context.register(
                StandardContributionPoints.TOOLS,
                "import_credential",
                new ImportCredentialTool(agent, vault));
        context.register(
                StandardContributionPoints.TOOLS,
                "read_credential",
                new ReadCredentialTool(agent, vault));
        context.register(
                StandardContributionPoints.AGENT_CONFIGURATION, "secret-agent", agent::configure);
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
    public String preferredToolName(@NonNull String localId) {
        return localId.equals("import_credential") ? "import_credential" : null;
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
