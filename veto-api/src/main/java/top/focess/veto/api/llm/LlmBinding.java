package top.focess.veto.api.llm;

import org.jspecify.annotations.NonNull;

/**
 * Host-selected provider/model configuration and a credential reference.
 *
 * @param provider provider family
 * @param model provider-specific model identifier
 * @param credentialKey opaque host credential reference, not secret material
 * @param options generation limits and sampling options
 * @param baseUrl optional endpoint override; {@code null} selects the provider default
 */
public record LlmBinding(
        @NonNull ProviderType provider,
        @NonNull String model,
        @NonNull String credentialKey,
        @NonNull LlmOptions options,
        String baseUrl) {}
