package top.focess.veto.llm.core;

import org.jspecify.annotations.NonNull;

/**
 * A single chat message in a compiled conversation — the role-mapped form the {@code
 * PromptCompiler} emits and the providers send. Provider adapters convert these into their
 * SDK-specific message types.
 *
 * @param role one of {@code system}, {@code user}, {@code assistant}, {@code tool}
 * @param content the message content (text; tool calls/responses are rendered as text observations)
 */
public record ChatMessage(@NonNull String role, @NonNull String content) {

    public static @NonNull ChatMessage system(@NonNull String content) {
        return new ChatMessage("system", content);
    }

    public static @NonNull ChatMessage user(@NonNull String content) {
        return new ChatMessage("user", content);
    }

    public static @NonNull ChatMessage assistant(@NonNull String content) {
        return new ChatMessage("assistant", content);
    }

    public static @NonNull ChatMessage tool(@NonNull String content) {
        return new ChatMessage("tool", content);
    }
}
