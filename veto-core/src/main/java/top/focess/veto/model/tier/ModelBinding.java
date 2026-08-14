package top.focess.veto.model.tier;

import org.jspecify.annotations.NonNull;
import top.focess.veto.llm.core.ProviderType;

/**
 * A concrete model binding resolved from a user's active model-tier profile for one {@link
 * ModelTier}. Carries everything a caller needs to build an {@code AgentRunner.LlmBinding}: the
 * provider, model id, the vault credential-key name (resolved per-user downstream), sampling
 * defaults, and an optional base-URL override.
 *
 * <p>The {@code credentialKey} is a {@link top.focess.veto.vault.KeysteadVault} SECURE_NOTE title -
 * a logical name, not the secret itself. Each user stores their own secret under that title.
 *
 * <p>The {@code baseUrl} overrides the provider's default base URL when set (non-null); when null
 * the caller falls back to the provider strategy's default. Each user configures their own base URL
 * per tier, so a user can point a tier at a self-hosted endpoint or a proxy without touching anyone
 * else's configuration.
 *
 * @param provider the LLM provider
 * @param model the model id
 * @param credentialKey vault secure-note title holding the API key
 * @param temperature the default sampling temperature
 * @param maxOutputTokens the default max output tokens
 * @param baseUrl the base-URL override (null -> provider default)
 */
public record ModelBinding(
        @NonNull ProviderType provider,
        @NonNull String model,
        @NonNull String credentialKey,
        double temperature,
        int maxOutputTokens,
        String baseUrl) {

    /** Convenience constructor for callers that do not override the base URL (null -> default). */
    public ModelBinding(
            @NonNull ProviderType provider,
            @NonNull String model,
            @NonNull String credentialKey,
            double temperature,
            int maxOutputTokens) {
        this(provider, model, credentialKey, temperature, maxOutputTokens, null);
    }
}
