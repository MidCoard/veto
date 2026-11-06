package top.focess.veto.llm.provider;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.llm.LlmRequestResolver;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ResolvedRequest;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;

/** Provider strategy with target resolution and normalized execution. */
public interface LLMProviderStrategy {
    /**
     * Returns whether this strategy supports the given provider type.
     *
     * @param providerType the provider type to check
     * @return true if supported, false otherwise
     */
    boolean supports(@NonNull ProviderType providerType);

    /**
     * The provider's own base URL, or {@code null} to use the SDK default. The egress strategy may
     * override this (e.g. to route through a broker), but providers declare their natural endpoint
     * here.
     *
     * @return the default base URL
     */
    String defaultBaseUrl();

    /** Resolves a request using provider-owned target semantics. */
    default @NonNull ResolvedRequest resolve(
            @NonNull VetoRequest request, @NonNull LlmRequestResolver network) {
        return network.resolve(request, defaultBaseUrl());
    }

    /**
     * Executes the LLM request using this strategy.
     *
     * @param request the resolved request containing the effective URL and API key
     * @return the response from the LLM
     */
    @NonNull VetoResponse execute(@NonNull ResolvedRequest request);
}
