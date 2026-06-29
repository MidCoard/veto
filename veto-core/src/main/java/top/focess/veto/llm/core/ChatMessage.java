package top.focess.veto.llm.core;

import org.jetbrains.annotations.NotNull;

/**
 * A single chat message in a compiled conversation — the role-mapped form the {@code
 * PromptCompiler} emits and the providers send. Provider adapters convert these into their
 * SDK-specific message types.
 *
 * @param role one of {@code system}, {@code user}, {@code assistant}, {@code tool}
 * @param content the message content (text; tool calls/responses are rendered as text observations)
 */
public record ChatMessage(@NotNull String role, @NotNull String content) {

    @NotNull
    public static ChatMessage system(@NotNull String content) {
        return new ChatMessage("system", content);
    }

    @NotNull
    public static ChatMessage user(@NotNull String content) {
        return new ChatMessage("user", content);
    }

    @NotNull
    public static ChatMessage assistant(@NotNull String content) {
        return new ChatMessage("assistant", content);
    }

    @NotNull
    public static ChatMessage tool(@NotNull String content) {
        return new ChatMessage("tool", content);
    }
}
