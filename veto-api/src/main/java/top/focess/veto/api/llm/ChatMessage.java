package top.focess.veto.api.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
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
 * @param toolSuccess nullable except on tool-result messages; true when the tool executed
 *     successfully and false when the result is a failure diagnostic.
 * @param sourceTurns internal turn numbers whose content contributed to this message
 * @param promptSources source spans used to validate citations in this message
 * @param nativeState optional opaque provider state retained for same-provider replay
 */
public record ChatMessage(
        @NonNull String role,
        @NonNull String content,
        String callId,
        String toolName,
        String toolArgs,
        String reasoningContent,
        Boolean toolSuccess,
        @JsonIgnore @NonNull List<Integer> sourceTurns,
        @JsonIgnore @NonNull List<PromptSpan> promptSources,
        @JsonIgnore NativeToolState nativeState) {

    /** Copies the provenance lists so callers cannot mutate a compiled message afterward. */
    public ChatMessage {
        sourceTurns = List.copyOf(sourceTurns);
        promptSources = List.copyOf(promptSources);
    }

    /**
     * Creates a message without opaque provider state.
     *
     * @param role provider role
     * @param content message text
     * @param callId optional call identifier
     * @param toolName optional called tool name
     * @param toolArgs optional JSON arguments
     * @param reasoningContent optional provider reasoning
     * @param toolSuccess optional tool-result status
     * @param sourceTurns contributing turn numbers
     * @param promptSources contributing source spans
     */
    public ChatMessage(
            @NonNull String role,
            @NonNull String content,
            String callId,
            String toolName,
            String toolArgs,
            String reasoningContent,
            Boolean toolSuccess,
            @NonNull List<Integer> sourceTurns,
            @NonNull List<PromptSpan> promptSources) {
        this(
                role,
                content,
                callId,
                toolName,
                toolArgs,
                reasoningContent,
                toolSuccess,
                sourceTurns,
                promptSources,
                null);
    }

    /**
     * Returns a copy with opaque provider state for replay.
     *
     * @param state provider state, or {@code null} to clear it
     * @return message with the requested state and unchanged text/provenance
     */
    public @NonNull ChatMessage withNativeState(NativeToolState state) {
        return new ChatMessage(
                role,
                content,
                callId,
                toolName,
                toolArgs,
                reasoningContent,
                toolSuccess,
                sourceTurns,
                promptSources,
                state);
    }

    /**
     * Creates a message without source spans or opaque provider state.
     *
     * @param role provider role
     * @param content message text
     * @param callId optional call identifier
     * @param toolName optional called tool name
     * @param toolArgs optional JSON arguments
     * @param reasoningContent optional provider reasoning
     * @param toolSuccess optional tool-result status
     * @param sourceTurns contributing turn numbers
     */
    public ChatMessage(
            @NonNull String role,
            @NonNull String content,
            String callId,
            String toolName,
            String toolArgs,
            String reasoningContent,
            Boolean toolSuccess,
            @NonNull List<Integer> sourceTurns) {
        this(
                role,
                content,
                callId,
                toolName,
                toolArgs,
                reasoningContent,
                toolSuccess,
                sourceTurns,
                List.of());
    }

    /**
     * Returns a copy with citation source spans.
     *
     * @param sources source spans to retain
     * @return message with copied spans and unchanged provider fields
     */
    public @NonNull ChatMessage withPromptSources(@NonNull List<PromptSpan> sources) {
        return new ChatMessage(
                role,
                content,
                callId,
                toolName,
                toolArgs,
                reasoningContent,
                toolSuccess,
                sourceTurns,
                sources,
                nativeState);
    }

    /**
     * Creates a message without provenance or opaque provider state.
     *
     * @param role provider role
     * @param content message text
     * @param callId optional call identifier
     * @param toolName optional called tool name
     * @param toolArgs optional JSON arguments
     * @param reasoningContent optional provider reasoning
     * @param toolSuccess optional tool-result status
     */
    public ChatMessage(
            @NonNull String role,
            @NonNull String content,
            String callId,
            String toolName,
            String toolArgs,
            String reasoningContent,
            Boolean toolSuccess) {
        this(role, content, callId, toolName, toolArgs, reasoningContent, toolSuccess, List.of());
    }

    /**
     * Returns a copy with internal source-turn provenance; never changes provider message text.
     *
     * @param turns contributing turn numbers
     * @return message with copied turn numbers
     */
    public @NonNull ChatMessage withSourceTurns(@NonNull List<Integer> turns) {
        return new ChatMessage(
                role,
                content,
                callId,
                toolName,
                toolArgs,
                reasoningContent,
                toolSuccess,
                turns,
                promptSources,
                nativeState);
    }

    // ── Backward-compatible factories (structured fields = null) ────────────

    /**
     * Creates a plain system message without tool or provenance fields.
     *
     * @param content system text
     * @return system message
     */
    public static @NonNull ChatMessage system(@NonNull String content) {
        return new ChatMessage("system", content, null, null, null, null, null);
    }

    /**
     * Creates a plain user message without tool or provenance fields.
     *
     * @param content user text
     * @return user message
     */
    public static @NonNull ChatMessage user(@NonNull String content) {
        return new ChatMessage("user", content, null, null, null, null, null);
    }

    /**
     * Creates a plain assistant message without tool or provenance fields.
     *
     * @param content assistant text
     * @return assistant message
     */
    public static @NonNull ChatMessage assistant(@NonNull String content) {
        return new ChatMessage("assistant", content, null, null, null, null, null);
    }

    /**
     * Creates an unbound historical tool message; prefer {@link #toolResult} when a call ID exists.
     *
     * @param content historical tool observation
     * @return unbound tool message
     */
    public static @NonNull ChatMessage tool(@NonNull String content) {
        return new ChatMessage("tool", content, null, null, null, null, null);
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
     * @return assistant message carrying the native tool call
     */
    public static @NonNull ChatMessage assistantToolCall(
            @NonNull String callId,
            @NonNull String toolName,
            @NonNull String toolArgs,
            @NonNull String content,
            String reasoningContent) {
        return new ChatMessage(
                "assistant", content, callId, toolName, toolArgs, reasoningContent, null);
    }

    /**
     * Creates a successful tool-result message linked by {@code callId}.
     *
     * @param callId matching assistant call identifier
     * @param content raw tool output
     * @return successful tool-result message
     */
    public static @NonNull ChatMessage toolResult(@NonNull String callId, @NonNull String content) {
        return toolResult(callId, content, true);
    }

    /**
     * Creates a tool-result message with status preserved for provider adapters.
     *
     * @param callId matching assistant call identifier
     * @param content raw tool output or failure diagnostic
     * @param success whether the tool executed successfully
     * @return tool-result message
     */
    public static @NonNull ChatMessage toolResult(
            @NonNull String callId, @NonNull String content, boolean success) {
        return new ChatMessage("tool", content, callId, null, null, null, success);
    }

    /**
     * Returns tool-result text for providers whose protocol has no native failure-status field.
     *
     * @return unchanged message content
     */
    public @NonNull String toolResultContentWithStatus() {
        return content;
    }
}
