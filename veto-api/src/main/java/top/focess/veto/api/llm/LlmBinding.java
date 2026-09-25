package top.focess.veto.api.llm;

import org.jspecify.annotations.NonNull;

/** Host-selected provider/model configuration and a credential reference. */
public record LlmBinding(
        @NonNull ProviderType provider,
        @NonNull String model,
        @NonNull String credentialKey,
        @NonNull LlmOptions options,
        String baseUrl) {}
