package top.focess.veto.llm.core;

import org.jspecify.annotations.NonNull;

/**
 * A {@link VetoRequest} after egress resolution. Only ever constructed inside the LLM module (by
 * the orchestrator) and handed to a provider — keeping the resolved secret off the public request
 * type and out of any audit/log surface.
 *
 * <p>{@code baseUrl} is the effective transport target chosen by the egress strategy: the
 * provider's own URL in direct mode, or the local broker URL in proxy mode. {@code apiKey} is
 * correspondingly the real secret (direct) or a low-value internal token (proxy).
 *
 * @param request the original VetoRequest
 * @param baseUrl the effective base URL for the call
 * @param apiKey the API key or internal token to use
 */
public record ResolvedRequest(
        @NonNull VetoRequest request, String baseUrl, @NonNull String apiKey) {
    /**
     * Returns the provider type from the underlying request.
     *
     * @return the provider type
     */
    public @NonNull ProviderType providerType() {
        return request.providerType();
    }

    /**
     * Returns the model name from the underlying request.
     *
     * @return the model name
     */
    public @NonNull String modelName() {
        return request.modelName();
    }

    /**
     * Returns the LLM options from the underlying request.
     *
     * @return the LLM options
     */
    public @NonNull LlmOptions options() {
        return request.options();
    }
}
