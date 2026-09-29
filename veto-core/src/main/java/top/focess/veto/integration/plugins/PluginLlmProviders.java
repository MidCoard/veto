package top.focess.veto.integration.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.focess.veto.api.llm.*;
import top.focess.veto.api.llm.exceptions.ModelCapabilityException;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.llm.provider.AbstractLlmProvider;
import top.focess.veto.llm.provider.LLMProviderStrategy;
import top.focess.veto.observability.AuditLogger;

/** Adapts installed provider contributions to core audit/retry orchestration. */
@Component
public final class PluginLlmProviders {
    private volatile @NonNull Map<ProviderType, LLMProviderStrategy> providers = Map.of();
    private final @NonNull ObjectMapper mapper;
    private final @NonNull AuditLogger audit;

    /** Adapts every installed provider contribution; duplicate provider types fail startup. */
    public PluginLlmProviders(
            @NonNull PluginManager manager,
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NonNull ObjectMapper mapper,
            @NonNull AuditLogger audit) {
        this.mapper = mapper;
        this.audit = audit;
        providers = build(manager, mapper, audit);
    }

    /** Rebuilds provider adapters from the currently published plugin catalog. */
    public synchronized void reload(@NonNull PluginManager manager) {
        providers = build(manager, mapper, audit);
    }

    private static @NonNull Map<ProviderType, LLMProviderStrategy> build(
            @NonNull PluginManager manager,
            @NonNull ObjectMapper mapper,
            @NonNull AuditLogger audit) {
        Map<ProviderType, LLMProviderStrategy> values = new HashMap<>();
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
            if (values.putIfAbsent(type, strategy) != null)
                throw new IllegalArgumentException("Duplicate LLM provider: " + type);
        }
        return Map.copyOf(values);
    }

    /** Returns the strategy for the given provider type; throws when none is registered. */
    public @NonNull LLMProviderStrategy require(@NonNull ProviderType type) {
        var provider = providers.get(type);
        if (provider == null)
            throw new ModelCapabilityException("No provider registered for type: " + type);
        return provider;
    }

    private record Outcome(LlmClient.RawCompletion result, Exception failure) {}
}
