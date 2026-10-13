package top.focess.veto.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;

/**
 * One incremental backend update. Completion and user approval have their own frames.
 *
 * <p><b>Thread safety:</b> the envelope has final references, but is only as safe to share as its
 * data. All data variants except {@link ToolCall} are immutable and thread-safe. ToolCall retains a
 * mutable JSON tree; neither it nor this envelope copies or synchronizes that tree. Finish
 * constructing it before publication and keep it read-only afterward, including while queued.
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
    public Delta {
        Objects.requireNonNull(scope, "scope");
        if (data instanceof AgentData && !(scope instanceof Scope.AgentScope))
            throw new IllegalArgumentException("Agent output requires AgentScope");
    }

    /** Creates an update before its session broker assigns a sequence. */
    public Delta(@NonNull Scope scope, @NonNull Data data) {
        this(scope, 0, Instant.now(), data);
    }

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

    /** Immutable, thread-safe rendering hint; percent is 0–100, or -1 when indeterminate. */
    public record Progress(
            @JsonProperty(value = "content", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String content,
            @JsonProperty(value = "percent", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int percent)
            implements Data {
        public static final int INDETERMINATE = -1;

        @JsonIgnore
        public boolean isIndeterminate() {
            return percent == INDETERMINATE;
        }
    }

    /** Immutable, thread-safe notice severity. */
    public enum Level {
        INFO,
        WARNING,
        ERROR
    }

    /** Immutable, thread-safe notice. */
    public record Notice(
            @JsonProperty(value = "text", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String text,
            @JsonProperty(value = "level", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull Level level)
            implements AgentData {}

    /**
     * Immutable, thread-safe measurement of one model call, not a cumulative total. Nullable cache
     * counts mean unreported.
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

    /** Immutable, thread-safe reasoning text. */
    public record Thought(
            @JsonProperty(value = "text", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String text,
            @JsonProperty(value = "turnNumber", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int turnNumber)
            implements AgentData {}

    /** Immutable, thread-safe message text. */
    public record Message(
            @JsonProperty(value = "text", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String text,
            @JsonProperty(value = "turnNumber", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int turnNumber)
            implements Data {}

    /**
     * Not unconditionally thread-safe: args retains and exposes a mutable JSON tree without copying
     * or synchronization. Keep the complete tree read-only after publication.
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

    /** Immutable, thread-safe tool result text and status. */
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
