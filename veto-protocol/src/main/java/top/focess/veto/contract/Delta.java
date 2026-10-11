package top.focess.veto.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;

/** One incremental backend update. Completion and user approval have their own frames. */
public record Delta(
        Scope scope,
        @JsonProperty(value = "sequence", required = true) @JsonSetter(nulls = Nulls.FAIL)
                long sequence,
        @JsonProperty(value = "emittedAt", required = true) @JsonSetter(nulls = Nulls.FAIL)
                @NonNull Instant emittedAt,
        @JsonProperty(value = "data", required = true) @JsonSetter(nulls = Nulls.FAIL)
                @NonNull Data data)
        implements Frame.ServerFrame, Frame.ScopedFrame {
    /** Creates an update before its session broker assigns a sequence. */
    public Delta(Scope scope, @NonNull Data data) {
        this(scope, 0, Instant.now(), data);
    }

    public static @NonNull Delta message(@NonNull String text) {
        return new Delta(null, new Message(text, null));
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Progress.class, name = "progress"),
        @JsonSubTypes.Type(value = Notice.class, name = "notice"),
        @JsonSubTypes.Type(value = Thought.class, name = "thought"),
        @JsonSubTypes.Type(value = Message.class, name = "message"),
        @JsonSubTypes.Type(value = ToolCall.class, name = "tool_call"),
        @JsonSubTypes.Type(value = ToolResult.class, name = "tool_result"),
        @JsonSubTypes.Type(value = RecordUpdated.class, name = "record_updated"),
        @JsonSubTypes.Type(value = SessionInvalidated.class, name = "session_invalidated"),
        @JsonSubTypes.Type(value = PluginEvent.class, name = "plugin_event"),
        @JsonSubTypes.Type(value = BreakerTripped.class, name = "breaker_tripped"),
    })
    public sealed interface Data
            permits Progress,
                    Notice,
                    Thought,
                    Message,
                    ToolCall,
                    ToolResult,
                    RecordUpdated,
                    SessionInvalidated,
                    PluginEvent,
                    BreakerTripped {}

    /** A rendering hint; percent is 0–100, or -1 when completion is indeterminate. */
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

    public record Notice(
            @JsonProperty(value = "text", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String text)
            implements Data {}

    public record Thought(
            @JsonProperty(value = "text", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String text,
            @JsonProperty(value = "turnNumber", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int turnNumber)
            implements Data {}

    public record Message(
            @JsonProperty(value = "text", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String text,
            Integer turnNumber)
            implements Data {}

    public record ToolCall(
            String callId,
            @JsonProperty(value = "tool", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String tool,
            @JsonProperty(value = "args", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull ObjectNode args,
            @JsonProperty(value = "turnNumber", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int turnNumber)
            implements Data {}

    public record ToolResult(
            String callId,
            @JsonProperty(value = "text", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String text,
            @JsonProperty(value = "success", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    boolean success,
            @JsonProperty(value = "turnNumber", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int turnNumber)
            implements Data {}

    public record RecordUpdated(
            @JsonProperty(value = "turnNumber", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int turnNumber)
            implements Data {}

    public record SessionInvalidated(
            @JsonProperty(value = "resources", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull List<@NonNull String> resources)
            implements Data {
        public SessionInvalidated {
            resources = List.copyOf(resources);
        }
    }

    public record PluginEvent(
            @JsonProperty(value = "topic", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String topic,
            @JsonProperty(value = "data", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull ObjectNode data)
            implements Data {}

    public record BreakerTripped(
            @JsonProperty(value = "text", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    @NonNull String text,
            @JsonProperty(value = "maxCallsPerEpisode", required = true)
                    @JsonSetter(nulls = Nulls.FAIL)
                    long maxCallsPerEpisode,
            @JsonProperty(value = "turnNumber", required = true) @JsonSetter(nulls = Nulls.FAIL)
                    int turnNumber)
            implements Data {}
}
