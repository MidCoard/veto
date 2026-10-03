package top.focess.veto.api.llm;

import org.jspecify.annotations.NonNull;

/** Provider transport adapter invoked after host credential and egress resolution. */
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

    /**
     * Sends one fully resolved request and returns undecoded provider output plus accounting data.
     *
     * @param request request with resolved model, endpoint, credential, and options
     * @return raw provider completion and accounting data
     * @throws Exception when transport or provider processing fails
     */
    LlmClient.@NonNull RawCompletion complete(@NonNull ResolvedRequest request) throws Exception;
}
