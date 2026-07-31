package top.focess.veto.model.tier;

import org.jspecify.annotations.NonNull;
import top.focess.veto.llm.core.ProviderType;

/**
 * A concrete model binding resolved from the active model-tier configuration for one {@link
 * ModelTier}. Carries everything a caller needs to build an {@code AgentRunner.LlmBinding}: the
 * provider, model id, the vault credential-key name (resolved per-user downstream), and sampling
 * defaults.
 *
 * <p>The {@code credentialKey} is a {@link top.focess.veto.vault.KeysteadVault} SECURE_NOTE title -
 * a logical name, not the secret itself. Each user stores their own secret under that title.
 *
 * @param provider the LLM provider
 * @param model the model id
 * @param credentialKey vault secure-note title holding the API key
 * @param temperature the default sampling temperature
 * @param maxOutputTokens the default max output tokens
 */
public record ModelBinding(
        @NonNull ProviderType provider,
        @NonNull String model,
        @NonNull String credentialKey,
        double temperature,
        int maxOutputTokens) {}
