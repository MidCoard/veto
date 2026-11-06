package top.focess.veto.api.llm;

/** Supported LLM providers. */
public enum ProviderType {
    /** OpenAI provider. */
    OPENAI,
    /** Anthropic provider. */
    ANTHROPIC,
    /** Google Gemini provider. */
    GEMINI,
    /** DeepSeek provider. */
    DEEPSEEK,
    /** Host-managed local model; never resolves a remote endpoint or vault API key. */
    LOCAL;

    /** Whether configured bindings require a network credential reference. */
    public boolean usesCredentials() {
        return this != LOCAL;
    }
}
