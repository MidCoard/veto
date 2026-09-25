package top.focess.veto.api.llm;

import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Standardized request for the Veto Agent Loop.
 *
 * <p>Carries compiled conversation messages and native tool definitions. Providers decode ordinary
 * text and native calls independently. When messages is empty, adapters use systemPrompt and
 * userPrompt.
 *
 * <p>Security note: this object intentionally does <b>not</b> carry a plaintext API key.
 * Credentials are referenced by {@code credentialKey} and resolved at call time by the {@code
 * CredentialResolver}, so secrets never live in a request object that may be logged, serialized, or
 * echoed into the audit trail.
 *
 * @param systemPrompt effective system instructions
 * @param userPrompt fallback user text for adapters without compiled messages
 * @param tools immutable native-tool definitions available for this call
 * @param providerType selected provider protocol
 * @param modelName exact provider model identifier
 * @param credentialKey host credential reference, never plaintext secret material
 * @param options sampling, output, context, and deadline options
 * @param messages immutable compiled conversation; empty selects the prompt fallback
 * @param baseUrl optional operator-configured provider endpoint
 * @param nativeToolsEnabled whether the adapter may send native tool definitions and calls
 * @param responseContract host-owned response validation contract
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
        String baseUrl,
        boolean nativeToolsEnabled,
        @NonNull ResponseContract responseContract) {

    /** Copies tool definitions and messages to prevent later caller mutation. */
    public VetoRequest {
        tools = List.copyOf(tools);
        messages = List.copyOf(messages);
    }

    /**
     * Returns whether this request carries a compiled multi-turn message list.
     *
     * @return whether {@link #messages()} is nonempty
     */
    public boolean hasMessages() {
        return !messages.isEmpty();
    }

    /**
     * Returns a copy using the supplied host-owned response validation contract.
     *
     * @param contract contract for the returned request
     * @return request with the same prompt, tools, and routing fields
     */
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
                baseUrl,
                nativeToolsEnabled,
                contract);
    }
}
