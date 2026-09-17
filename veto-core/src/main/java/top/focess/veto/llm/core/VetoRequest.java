package top.focess.veto.llm.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Standardized request for the Veto Agent Loop.
 *
 * <p>Carries compiled conversation messages and native tool definitions. Providers decode ordinary
 * text and native calls independently. The legacy responseSchema slot is retained for source
 * compatibility; the conversation compiler leaves it null and adapters do not impose it on model
 * text. When messages is empty, adapters use systemPrompt and userPrompt.
 *
 * <p>Security note: this object intentionally does <b>not</b> carry a plaintext API key.
 * Credentials are referenced by {@code credentialKey} and resolved at call time by the {@code
 * CredentialResolver}, so secrets never live in a request object that may be logged, serialized, or
 * echoed into the audit trail.
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
        String baseUrl,
        boolean nativeToolsEnabled,
        @NonNull ResponseContract responseContract) {

    public VetoRequest(
            @NonNull String systemPrompt,
            @NonNull String userPrompt,
            @NonNull List<@NonNull ToolDefinition> tools,
            @NonNull ProviderType providerType,
            @NonNull String modelName,
            @NonNull String credentialKey,
            @NonNull LlmOptions options,
            @NonNull List<@NonNull ChatMessage> messages,
            JsonNode responseSchema,
            String baseUrl,
            boolean nativeToolsEnabled) {
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
                baseUrl,
                nativeToolsEnabled,
                ResponseContract.ordinary());
    }

    public VetoRequest(
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
                baseUrl,
                true);
    }

    public VetoRequest {
        tools = List.copyOf(tools);
        messages = List.copyOf(messages);
        responseSchema = responseSchema == null ? null : responseSchema.deepCopy();
    }

    /** Whether this request carries a compiled multi-turn message list. */
    public boolean hasMessages() {
        return !messages.isEmpty();
    }

    public @NonNull VetoRequest withResponseContract(@NonNull ResponseContract contract) {
        return new VetoRequest(
                systemPrompt,
                userPrompt,
                tools,
                providerType,
                modelName,
                credentialKey,
                options,
                messages,
                responseSchema,
                baseUrl,
                nativeToolsEnabled,
                contract);
    }
}
