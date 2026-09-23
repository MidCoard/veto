package top.focess.veto.api.llm;

import org.jspecify.annotations.NonNull;

/** Provider transport adapter invoked after host credential and egress resolution. */
public interface LlmProvider {
    @NonNull ProviderType type();

    String defaultBaseUrl();

    LlmClient.@NonNull RawCompletion complete(@NonNull ResolvedRequest request) throws Exception;
}
