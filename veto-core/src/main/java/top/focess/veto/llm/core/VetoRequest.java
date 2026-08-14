package top.focess.veto.llm.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Standardized request for the Veto Agent Loop.
 *
 * <p>Carries the compiled conversation ({@link #messages}) and the per-turn {@link #responseSchema}
 * (the {@code veto_pulse} schema variant) produced by the {@code PromptCompiler} via the {@link
 * top.focess.veto.agent.translation.CapabilityTranslator}, alongside the flat {@link #tools} list.
 * When {@link #messages} is non-empty, providers build the API call from it (role mapping,
 * multi-turn); otherwise they fall back to {@link #systemPrompt} + {@link #userPrompt}. When {@link
 * #responseSchema} is present, providers use it as the {@code response_format}; otherwise they fall
 * back to a provider default.
 *
 * <p>Security note: this object intentionally does <b>not</b> carry a plaintext API key. Per the
 * Trust Model and the vault Vault design, credentials are referenced by {@code credentialKey} and
 * resolved at call time by the {@code CredentialResolver}, so secrets never live in a request
 * object that may be logged, serialized, or echoed into the audit trail.
 */
public record VetoRequest(
        @NonNull String systemPrompt,
        @NonNull String userPrompt,
        @NonNull List<@NonNull ToolDefinition> tools,
        @NonNull ProviderType providerType,
        @NonNull String modelName,
        @NonNull String credentialKey,
        @NonNull LlmOptions options,
        @NonNull List<@NonNull ChatMessage> messages,
        JsonNode responseSchema,
        String baseUrl) {

    /**
     * 9-arg convenience: no per-turn schema and no base-URL override (null -> provider default).
     */
    public VetoRequest(
            @NonNull String systemPrompt,
            @NonNull String userPrompt,
            @NonNull List<@NonNull ToolDefinition> tools,
            @NonNull ProviderType providerType,
            @NonNull String modelName,
            @NonNull String credentialKey,
            @NonNull LlmOptions options,
            @NonNull List<@NonNull ChatMessage> messages,
            JsonNode responseSchema) {
        this(
                systemPrompt,
                userPrompt,
                tools,
                providerType,
                modelName,
                credentialKey,
                options,
                messages,
                responseSchema,
                null);
    }

    /**
     * Backwards-compatible constructor (single-turn system+user; no per-turn schema, no base URL).
     */
    public VetoRequest(
            @NonNull String systemPrompt,
            @NonNull String userPrompt,
            @NonNull List<@NonNull ToolDefinition> tools,
            @NonNull ProviderType providerType,
            @NonNull String modelName,
            @NonNull String credentialKey,
            @NonNull LlmOptions options) {
        this(
                systemPrompt,
                userPrompt,
                tools,
                providerType,
                modelName,
                credentialKey,
                options,
                List.of(),
                null,
                null);
    }

    /** Whether this request carries a compiled multi-turn message list. */
    public boolean hasMessages() {
        return !messages.isEmpty();
    }
}
