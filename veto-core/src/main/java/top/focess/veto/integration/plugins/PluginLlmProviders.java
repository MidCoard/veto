package top.focess.veto.integration.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.focess.veto.api.llm.*;
import top.focess.veto.api.llm.exceptions.ModelCapabilityException;
import top.focess.veto.api.plugin.PluginBinding;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.llm.provider.AbstractLlmProvider;
import top.focess.veto.llm.provider.LLMProviderStrategy;
import top.focess.veto.observability.AuditLogger;
import top.focess.veto.plugin.runtime.PluginLifecycle;
import top.focess.veto.plugin.runtime.ScriptPlugin;

/** Adapts installed provider contributions to core audit/retry orchestration. */
@Component
public final class PluginLlmProviders {
    private volatile @NonNull Map<ProviderType, RegisteredProvider> providers = Map.of();
    private final @NonNull ObjectMapper mapper;
    private final @NonNull AuditLogger audit;
    private final @NonNull PluginManager manager;
    private final @NonNull SessionPlugins selections;

    /** Adapts every installed provider contribution; duplicate provider types fail startup. */
    public PluginLlmProviders(
            @NonNull PluginManager manager,
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NonNull ObjectMapper mapper,
            @NonNull AuditLogger audit,
            @NonNull SessionPlugins selections) {
        this.manager = manager;
        this.mapper = mapper;
        this.audit = audit;
        this.selections = selections;
        providers = build(manager, mapper, audit);
    }

    /** Rebuilds provider adapters from the currently published plugin catalog. */
    public synchronized void reload(@NonNull PluginManager manager) {
        providers = build(manager, mapper, audit);
    }

    private static @NonNull Map<ProviderType, RegisteredProvider> build(
            @NonNull PluginManager manager,
            @NonNull ObjectMapper mapper,
            @NonNull AuditLogger audit) {
        Map<ProviderType, RegisteredProvider> values = new HashMap<>();
        for (var entry : manager.catalog().entries(StandardContributionPoints.LLM_PROVIDERS)) {
            var implementation = entry.implementation();
            var runtime = manager.plugin(entry.source().namespace());
            var type = implementation.type();
            var strategy =
                    new AbstractLlmProvider(mapper, audit) {
                        public boolean supports(@NonNull ProviderType candidate) {
                            return candidate == type;
                        }

                        public String defaultBaseUrl() {
                            return implementation.defaultBaseUrl();
                        }

                        protected @NonNull String providerName() {
                            return type.name();
                        }

                        protected LlmClient.@NonNull RawCompletion invoke(
                                @NonNull ResolvedRequest request) throws Exception {
                            Outcome outcome =
                                    runtime.execute(
                                            () -> {
                                                try {
                                                    return new Outcome(
                                                            implementation.complete(request), null);
                                                } catch (Exception failure) {
                                                    return new Outcome(null, failure);
                                                }
                                            });
                            if (outcome.failure() != null) throw outcome.failure();
                            if (outcome.result() == null)
                                throw new ModelCapabilityException(
                                        "Provider returned no completion");
                            return outcome.result();
                        }
                    };
            if (values.putIfAbsent(type, new RegisteredProvider(strategy, binding(runtime)))
                    != null) throw new IllegalArgumentException("Duplicate LLM provider: " + type);
        }
        return Map.copyOf(values);
    }

    /** Returns the strategy for the given provider type; throws when none is registered. */
    public @NonNull LLMProviderStrategy require(@NonNull ProviderType type) {
        var provider = providers.get(type);
        if (provider == null)
            throw new ModelCapabilityException("No provider registered for type: " + type);
        return provider.strategy();
    }

    /** Resolves a provider only when this session pinned its exact contributing plugin revision. */
    public @NonNull LLMProviderStrategy require(
            @NonNull ProviderType type, @NonNull String sessionId) {
        var provider = providers.get(type);
        if (provider == null) throw unavailable(type, "its provider plugin is not loaded");
        var selected = selections.bindings(sessionId);
        PluginBinding required = provider.plugin();
        var pinned =
                selected.stream()
                        .filter(value -> manager.canonicalId(value.id()).equals(required.id()))
                        .findFirst()
                        .orElse(null);
        if (pinned == null)
            throw unavailable(type, "the provider plugin was not selected for this session");
        if (!pinned.version().equals(required.version())
                || !pinned.revision().equals(required.revision()))
            throw unavailable(
                    type,
                    "the session requires plugin "
                            + pinned.id()
                            + " "
                            + pinned.version()
                            + " revision "
                            + pinned.revision()
                            + ", but the loaded revision is "
                            + required.revision());
        return provider.strategy();
    }

    private static @NonNull ModelCapabilityException unavailable(
            @NonNull ProviderType type, @NonNull String reason) {
        return new ModelCapabilityException(
                "Model provider "
                        + type
                        + " is unavailable for this session because "
                        + reason
                        + ". Restore the pinned plugin revision or choose an available model before retrying.");
    }

    private static @NonNull PluginBinding binding(@NonNull PluginLifecycle plugin) {
        String revision =
                plugin.implementation() instanceof ScriptPlugin script
                        ? script.digest()
                        : plugin.identity().version();
        return new PluginBinding(plugin.identity().id(), plugin.identity().version(), revision);
    }

    private record Outcome(LlmClient.RawCompletion result, Exception failure) {}

    private record RegisteredProvider(
            @NonNull LLMProviderStrategy strategy, @NonNull PluginBinding plugin) {}
}
