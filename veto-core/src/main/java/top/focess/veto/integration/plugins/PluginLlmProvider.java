package top.focess.veto.integration.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.LlmClient;
import top.focess.veto.api.llm.LlmProvider;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ResolvedRequest;
import top.focess.veto.api.llm.exceptions.ModelCapabilityException;
import top.focess.veto.llm.provider.AbstractLlmProvider;
import top.focess.veto.observability.AuditLogger;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/** LLM provider adapter bound to one admitted plugin activation. */
final class PluginLlmProvider extends AbstractLlmProvider {
    private final @NonNull LlmProvider implementation;
    private final @NonNull ManagedPlugin runtime;
    private final @NonNull ProviderType type;
    private final String defaultBaseUrl;

    PluginLlmProvider(
            @NonNull ObjectMapper mapper,
            @NonNull AuditLogger audit,
            @NonNull LlmProvider implementation,
            @NonNull ManagedPlugin runtime,
            @NonNull ProviderType type,
            String defaultBaseUrl) {
        super(mapper, audit);
        this.implementation = implementation;
        this.runtime = runtime;
        this.type = type;
        this.defaultBaseUrl = defaultBaseUrl;
    }

    public boolean supports(@NonNull ProviderType candidate) {
        return candidate == type;
    }

    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    public @NonNull String providerName() {
        return type.name();
    }

    public LlmClient.@NonNull RawCompletion invoke(@NonNull ResolvedRequest request)
            throws Exception {
        PluginCompletionResult outcome =
                runtime.execute(
                        () -> {
                            try {
                                return new PluginCompletionResult(
                                        implementation.complete(request), null);
                            } catch (Exception failure) {
                                return new PluginCompletionResult(null, failure);
                            }
                        });
        if (outcome.failure() != null) throw outcome.failure();
        if (outcome.result() == null)
            throw new ModelCapabilityException("Provider returned no completion");
        return outcome.result();
    }

    /** Provider completion or checked failure captured across plugin admission. */
    private record PluginCompletionResult(LlmClient.RawCompletion result, Exception failure) {}
}
