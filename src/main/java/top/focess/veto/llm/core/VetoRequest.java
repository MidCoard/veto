package top.focess.veto.llm.core;

import java.util.List;

/**
 * Standardized request for the Veto Agent Loop.
 *
 * <p>Security note: this object intentionally does <b>not</b> carry a plaintext API key. Per the
 * Trust Model and the C8 Vault design, credentials are referenced by {@code credentialKey} and
 * resolved at call time by the {@code CredentialResolver}, so secrets never live in a request
 * object that may be logged, serialized, or echoed into the audit trail.
 *
 * @param systemPrompt the system instructions for the LLM
 * @param userPrompt the user input for the LLM
 * @param tools the list of available tools
 * @param providerType the target provider type
 * @param modelName the name of the model to use
 * @param credentialKey the key to resolve the credential from the vault
 * @param options the LLM options (temperature, max tokens, etc.)
 */
public record VetoRequest(
        String systemPrompt,
        String userPrompt,
        List<ToolDefinition> tools,
        ProviderType providerType,
        String modelName,
        String credentialKey,
        LlmOptions options) {
    public VetoRequest {
        if (options == null) {
            options = LlmOptions.defaults();
        }
    }
}
