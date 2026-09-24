package top.focess.veto.api.llm;

import org.jspecify.annotations.NonNull;

/** Host-selected provider/model configuration and a credential reference. */
public record LlmBinding(
        @NonNull ProviderType provider,
        @NonNull String model,
        @NonNull String credentialKey,
        @NonNull LlmOptions options,
        String systemPromptBase,
        String baseUrl) {

    /** Convenience constructor for callers that do not override the base URL (null -> default). */
    public LlmBinding(
            @NonNull ProviderType provider,
            @NonNull String model,
            @NonNull String credentialKey,
            @NonNull LlmOptions options,
            String systemPromptBase) {
        this(provider, model, credentialKey, options, systemPromptBase, null);
    }
}
