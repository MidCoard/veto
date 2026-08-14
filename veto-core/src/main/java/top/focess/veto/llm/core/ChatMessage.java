package top.focess.veto.llm.core;

import org.jspecify.annotations.NonNull;

/**
 * A single chat message in a compiled conversation - the role-mapped form the {@code
 * PromptCompiler} emits and the providers send. Provider adapters convert these into their
 * SDK-specific message types.
 *
 * @param role one of {@code system}, {@code user}, {@code assistant}, {@code tool}
 * @param content the message content (text; may be empty for a tool-call-only assistant message
 *     where the call info is in the structured {@code toolName}/{@code toolArgs} fields)
 * @param callId nullable - links a tool-call assistant message to its tool-result message.
 * @param toolName nullable - the function name, set only on tool-call assistant messages.
 * @param toolArgs nullable - the call's arguments as a JSON string, set only on tool-call assistant
 *     messages.
 * @param reasoningContent nullable - the provider's reasoning content (DeepSeek thinking mode).
 *     Must be echoed back on the assistant message so the API accepts the conversation history.
 */
public record ChatMessage(
        @NonNull String role,
        @NonNull String content,
        String callId,
        String toolName,
        String toolArgs,
        String reasoningContent) {

    // ── Backward-compatible factories (structured fields = null) ────────────

    public static @NonNull ChatMessage system(@NonNull String content) {
        return new ChatMessage("system", content, null, null, null, null);
    }

    public static @NonNull ChatMessage user(@NonNull String content) {
        return new ChatMessage("user", content, null, null, null, null);
    }

    public static @NonNull ChatMessage assistant(@NonNull String content) {
        return new ChatMessage("assistant", content, null, null, null, null);
    }

    public static @NonNull ChatMessage tool(@NonNull String content) {
        return new ChatMessage("tool", content, null, null, null, null);
    }

    // ── Tool-call / tool-result factories (with callId binding) ─────────────

    /**
     * An assistant message that issued a tool call, with optional thought content and reasoning.
     *
     * @param callId the call id (links to the matching {@link #toolResult} message)
     * @param toolName the function name
     * @param toolArgs the arguments as a JSON string
     * @param content optional text alongside the tool call (e.g. a thought); empty if none
     * @param reasoningContent optional provider reasoning (DeepSeek thinking mode); null if none
     */
    public static @NonNull ChatMessage assistantToolCall(
            @NonNull String callId,
            @NonNull String toolName,
            @NonNull String toolArgs,
            @NonNull String content,
            String reasoningContent) {
        return new ChatMessage("assistant", content, callId, toolName, toolArgs, reasoningContent);
    }

    /** A tool-result message carrying the raw output of a tool call, linked by {@code callId}. */
    public static @NonNull ChatMessage toolResult(@NonNull String callId, @NonNull String content) {
        return new ChatMessage("tool", content, callId, null, null, null);
    }
}
