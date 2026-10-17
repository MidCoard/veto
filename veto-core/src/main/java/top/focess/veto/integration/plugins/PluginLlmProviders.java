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
import top.focess.veto.llm.provider.LLMProviderStrategy;
import top.focess.veto.observability.AuditLogger;

/** Adapts installed provider contributions to core audit/retry orchestration. */
@Component
public final class PluginLlmProviders {
    private volatile @NonNull Map<ProviderType, RegisteredLlmProvider> providers;
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
        providers = build(manager.registry(), mapper, audit);
    }

    /** Rebuilds provider adapters from the currently published plugin catalog. */
    public synchronized void reload(@NonNull PluginRegistry state) {
        providers = build(state, mapper, audit);
    }

    // WHY: adapters borrow the manager-owned activation; they must not close it.
    @SuppressWarnings("resource")
    private static @NonNull Map<ProviderType, RegisteredLlmProvider> build(
            @NonNull PluginRegistry state,
            @NonNull ObjectMapper mapper,
            @NonNull AuditLogger audit) {
        Map<ProviderType, RegisteredLlmProvider> values = new HashMap<>();
        for (var entry : state.entries(StandardContributionPoints.LLM_PROVIDERS)) {
            var implementation = entry.implementation();
            var runtime = state.plugin(entry.source().namespace());
            var type = implementation.type();
            String defaultBaseUrl = implementation.defaultBaseUrl();
            var strategy =
                    new PluginLlmProvider(
                            mapper, audit, implementation, runtime, type, defaultBaseUrl);
            if (values.putIfAbsent(type, new RegisteredLlmProvider(strategy, runtime.binding()))
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
        var publication = manager.registry();
        var selected = selections.bindings(sessionId);
        PluginBinding required = provider.plugin();
        var pinned =
                selected.stream()
                        .filter(value -> publication.canonicalId(value.id()).equals(required.id()))
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

    /** LLM strategy and the exact plugin binding that published it. */
    private record RegisteredLlmProvider(
            @NonNull LLMProviderStrategy strategy, @NonNull PluginBinding plugin) {}
}
