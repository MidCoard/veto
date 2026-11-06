package top.focess.veto.api.llm;

import org.jspecify.annotations.NonNull;

/**
 * Provider-resolved request. Network resolution keeps plaintext credentials out of VetoRequest;
 * LOCAL resolution carries a filesystem model endpoint and no credential.
 *
 * @param request original request
 * @param baseUrl provider target: network URL or local model path
 * @param apiKey resolved network secret/internal token, or empty for a local artifact
 */
public record ResolvedRequest(
        @NonNull VetoRequest request, String baseUrl, @NonNull String apiKey) {
    /**
     * Returns a diagnostic view that never includes the resolved credential.
     *
     * @return provider and model identifiers without credential or request content
     */
    @Override
    public @NonNull String toString() {
        return "ResolvedRequest[provider=" + providerType() + ", model=" + modelName() + "]";
    }

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
