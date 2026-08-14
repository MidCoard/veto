package top.focess.veto.llm.provider;

import org.jspecify.annotations.NonNull;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ResolvedRequest;
import top.focess.veto.llm.core.VetoResponse;

/**
 * Strategy for a single LLM provider. Implementations receive a {@link ResolvedRequest} (credential
 * already resolved by the orchestrator) and must never see the public {@code VetoRequest}, so a
 * provider cannot accidentally leak or re-log a raw secret.
 */
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

    /**
     * Executes the LLM request using this strategy.
     *
     * @param request the resolved request containing the effective URL and API key
     * @return the response from the LLM
     */
    @NonNull VetoResponse execute(@NonNull ResolvedRequest request);
}
