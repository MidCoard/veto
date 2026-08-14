package top.focess.veto.session;

import org.jspecify.annotations.NonNull;
import top.focess.veto.llm.core.ProviderType;

/**
 * Resolved LLM configuration for an active session's primary agent: the provider, model,
 * credential-key reference (the secret lives in {@code KeysteadVault}), and an optional base-URL
 * override. Frozen from the user's active model-tier profile at activation; re-resolved on every
 * prompt so switching the active profile ({@code /modeltier use}) takes effect immediately.
 *
 * <p>Lives in the {@code session} package (not on {@code PromptHandler}) so the session layer and
 * the command/transport layer both depend on it without a cycle.
 */
public record LlmConfig(
        @NonNull ProviderType provider,
        @NonNull String model,
        @NonNull String credKey,
        String baseUrl) {

    /** Convenience constructor for configs that do not override the base URL (null -> default). */
    public LlmConfig(
            @NonNull ProviderType provider, @NonNull String model, @NonNull String credKey) {
        this(provider, model, credKey, null);
    }
}
