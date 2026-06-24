package top.focess.veto.llm.core;

/**
 * A single chat message in a compiled conversation — the role-mapped form the {@code
 * PromptCompiler} emits and the providers send (LLD {@code prompt_compiler.md} §3.2). Provider
 * adapters convert these into their SDK-specific message types.
 *
 * @param role one of {@code system}, {@code user}, {@code assistant}, {@code tool}
 * @param content the message content (text; tool calls/responses are rendered as text observations)
 */
public record ChatMessage(String role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    public static ChatMessage tool(String content) {
        return new ChatMessage("tool", content);
    }
}
