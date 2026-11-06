package top.focess.veto.api.llm;

import org.jspecify.annotations.NonNull;

/** Provider adapter owning target resolution and model transport. */
public interface LlmProvider {
    /**
     * Identifies the provider protocol handled by this adapter.
     *
     * @return the provider family handled by this adapter
     */
    @NonNull ProviderType type();

    /**
     * Resolves the provider's default API endpoint.
     *
     * @return the provider's default endpoint, or {@code null} when none exists
     */
    String defaultBaseUrl();

    /** Resolves this provider's target; network providers use the host credential/egress policy. */
    default @NonNull ResolvedRequest resolve(
            @NonNull VetoRequest request, @NonNull LlmRequestResolver network) {
        return network.resolve(request, defaultBaseUrl());
    }

    /**
     * Sends one fully resolved request and returns undecoded provider output plus accounting data.
     *
     * @param request request with resolved model, endpoint, credential, and options
     * @return raw provider completion and accounting data
     * @throws Exception when transport or provider processing fails
     */
    LlmClient.@NonNull RawCompletion complete(@NonNull ResolvedRequest request) throws Exception;
}
