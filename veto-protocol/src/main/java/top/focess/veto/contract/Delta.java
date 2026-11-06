package top.focess.veto.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;

/**
 * One incremental backend update. Completion and user approval have their own frames.
 *
 * <p><b>Thread safety:</b> the envelope has final references, but is only as safe to share as its
 * data. All data variants except {@link ToolCall} are immutable and thread-safe. ToolCall retains a
 * mutable JSON tree; neither it nor this envelope copies or synchronizes that tree. Finish
 * constructing it before publication and keep it read-only afterward, including while queued.
 *
 * @param scope output owner; AgentData requires an agent scope
 * @param sequence output-order marker assigned by the publishing session or connection; zero for an
 *     update not yet sequenced; independent of request/reply seq
 * @param emittedAt wall-clock timestamp attached to this update by its producer
 * @param data incremental content to render; does not settle a command or agent turn
 */
public record Delta(
        @JsonProperty(value = "scope", required = true) @JsonSetter(nulls = Nulls.FAIL)
                @NonNull Scope scope,
        @JsonProperty(value = "sequence", required = true) @JsonSetter(nulls = Nulls.FAIL)
                long sequence,
        @JsonProperty(value = "emittedAt", required = true) @JsonSetter(nulls = Nulls.FAIL)
                @NonNull Instant emittedAt,
        @JsonProperty(value = "data", required = true) @JsonSetter(nulls = Nulls.FAIL)
                @NonNull Data data)
        implements Frame.ServerFrame, Frame.ScopedFrame {
    /**
     * Creates output with explicit ordering metadata and verifies agent output has an agent owner.
     *
     * @param scope output owner; AgentData requires an agent scope
     * @param sequence publisher's output-order marker, or zero before sequencing
     * @param emittedAt producer-supplied timestamp
     * @param data incremental content, kept read-only after publication
     * @throws IllegalArgumentException if AgentData is paired with a non-agent scope
     */
    public Delta {
        if (data instanceof AgentData && !(scope instanceof Scope.AgentScope))
            throw new IllegalArgumentException("Agent output requires AgentScope");
    }

    /**
     * Creates unsequenced output for the session/connection publisher, which assigns output order.
     *
     * @param scope output owner; AgentData requires an agent scope
     * @param data content to publish, with any mutable arguments finalized before publication
     * @throws IllegalArgumentException if AgentData is paired with a non-agent scope
     */
    public Delta(@NonNull Scope scope, @NonNull Data data) {
        this(scope, 0, Instant.now(), data);
    }

    /**
     * Creates connection-local message output without an agent turn.
     *
     * @param text message text to display
     * @return unsequenced global message with turnNumber -1 and the current timestamp
     */
    public static @NonNull Delta message(@NonNull String text) {
        return new Delta(new Scope.GlobalScope(), new Message(text, -1));
    }

    /**
     * Data sharing follows the enclosing Delta contract; ToolCall is the mutable-payload exception.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Progress.class, name = "progress"),
        @JsonSubTypes.Type(value = Notice.class, name = "notice"),
        @JsonSubTypes.Type(value = TokenUsage.class, name = "token_usage"),
        @JsonSubTypes.Type(value = Thought.class, name = "thought"),
        @JsonSubTypes.Type(value = Message.class, name = "message"),
        @JsonSubTypes.Type(value = ToolCall.class, name = "tool_call"),
        @JsonSubTypes.Type(value = ToolResult.class, name = "tool_result"),
    })
    public sealed interface Data permits Progress, AgentData, Message {}

    /**
     * Output tied to an actual agent; Delta rejects any other enclosing scope. Thread safety
     * depends on the variant: ToolCall retains mutable arguments; the other variants are immutable.
     */
    public sealed interface AgentData extends Data
            permits Notice, Thought, ToolCall, ToolResult, TokenUsage {}

    /**
     * Reports progress for display without completing the operation.
     *
     * @param content description of the work in progress
     * @param percent progress percentage by convention 0 through 100, or -1 when indeterminate
     */
    public record Progress(
            @JsonProperty(value = "content", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String content,
            @JsonProperty(value = "percent", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int percent)
            implements Data {
        /** Progress is active but has no numeric percentage. */
        public static final int INDETERMINATE = -1;

        /**
         * Selects indeterminate rendering rather than a percentage bar.
         *
         * @return whether percent equals INDETERMINATE
         */
        @JsonIgnore
        public boolean isIndeterminate() {
            return percent == INDETERMINATE;
        }
    }

    /** Immutable, thread-safe notice severity. */
    public enum Level {
        /** Informational output requiring no warning treatment. */
        INFO,
        /** Condition that deserves the user's attention. */
        WARNING,
        /** Reported failure; this display severity does not itself end a turn. */
        ERROR
    }

    /**
     * Displays an agent notice independently of its streamed answer.
     *
     * @param text notice text
     * @param level display severity
     */
    public record Notice(
            @JsonProperty(value = "text", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String text,
            @JsonProperty(value = "level", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Level level)
            implements AgentData {}

    /**
     * Immutable, thread-safe measurement of one model call, not a cumulative total. Nullable cache
     * counts mean unreported.
     *
     * @param modelCallId identifier of the measured model call
     * @param inputTokens input token count reported for this call
     * @param outputTokens output token count reported for this call
     * @param cacheReadInputTokens input tokens read from cache, or null when unreported
     * @param cacheCreationInputTokens input tokens used to create cache, or null when unreported
     * @param contextMaxTokens configured context capacity in tokens
     * @param model model identifier, or null when unavailable
     * @param provider provider identifier, or null when unavailable
     * @param purpose call purpose label, or null when unavailable
     * @param turnNumber agent turn associated with this measurement
     */
    public record TokenUsage(
            @JsonProperty(value = "modelCallId", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String modelCallId,
            @JsonProperty(value = "inputTokens", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    long inputTokens,
            @JsonProperty(value = "outputTokens", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    long outputTokens,
            Long cacheReadInputTokens,
            Long cacheCreationInputTokens,
            @JsonProperty(value = "contextMaxTokens", required = true)
                    @JsonSetter(nulls = Nulls.FAIL)
                    long contextMaxTokens,
            String model,
            String provider,
            String purpose,
            @JsonProperty(value = "turnNumber", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int turnNumber)
            implements AgentData {}

    /**
     * Appends reasoning text to an agent turn's output.
     *
     * @param text reasoning text fragment
     * @param turnNumber agent turn receiving the fragment
     */
    public record Thought(
            @JsonProperty(value = "text", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String text,
            @JsonProperty(value = "turnNumber", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int turnNumber)
            implements AgentData {}

    /**
     * Appends user-visible text without signalling completion.
     *
     * @param text message text fragment
     * @param turnNumber agent turn receiving the fragment, or -1 for output outside an agent turn
     */
    public record Message(
            @JsonProperty(value = "text", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String text,
            @JsonProperty(value = "turnNumber", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int turnNumber)
            implements Data {}

    /**
     * Not unconditionally thread-safe: args retains and exposes a mutable JSON tree without copying
     * or synchronization. Keep the complete tree read-only after publication.
     *
     * @param callId tool call identifier for matching its result, or null when unavailable
     * @param tool invoked tool name
     * @param args tool arguments retained by reference for display
     * @param turnNumber agent turn containing the tool call
     */
    public record ToolCall(
            String callId,
            @JsonProperty(value = "tool", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String tool,
            @JsonProperty(value = "args", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull ObjectNode args,
            @JsonProperty(value = "turnNumber", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int turnNumber)
            implements AgentData {}

    /**
     * Reports tool result text and status for display; does not settle the surrounding agent turn.
     *
     * @param callId corresponding tool call identifier, or null when unavailable
     * @param text rendered tool output or error description
     * @param success whether the tool operation succeeded
     * @param turnNumber agent turn containing the tool result
     */
    public record ToolResult(
            String callId,
            @JsonProperty(value = "text", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String text,
            @JsonProperty(value = "success", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    boolean success,
            @JsonProperty(value = "turnNumber", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int turnNumber)
            implements AgentData {}
}
