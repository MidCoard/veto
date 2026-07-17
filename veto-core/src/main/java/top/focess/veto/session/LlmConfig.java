package top.focess.veto.session;

import top.focess.veto.llm.core.ProviderType;

/**
 * Resolved LLM configuration for an active session's primary agent: the provider, model, and
 * credential-key reference (the secret lives in {@code KeysteadVault}). Frozen from the {@link
 * top.focess.veto.model.AgentEntity} at activation.
 *
 * <p>Lives in the {@code session} package (not on {@code PromptHandler}) so the session layer and
 * the command/transport layer both depend on it without a cycle.
 */
public record LlmConfig(ProviderType provider, String model, String credKey) {}
