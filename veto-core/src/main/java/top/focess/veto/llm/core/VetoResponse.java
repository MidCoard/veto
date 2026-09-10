package top.focess.veto.llm.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/**
 * A model turn: execute catalog calls, submit a guided program, or answer with a message. The
 * session controls whether guide is available; a response cannot enable that capability. Calls and
 * guide are mutually exclusive. Thought is an optional operational rationale.
 */
public record VetoResponse(
        String thought,
        List<@NonNull ToolCall> calls,
        String message,
        Guide guide,
        List<@NonNull Citation> citations) {

    public VetoResponse {
        if (citations != null) citations = List.copyOf(citations);
    }

    public VetoResponse(
            String thought, List<@NonNull ToolCall> calls, String message, Guide guide) {
        this(thought, calls, message, guide, null);
    }

    public record Citation(@NonNull String id, @NonNull List<@NonNull Source> sources) {
        public Citation {
            if (!id.matches("[A-Za-z0-9_-]{1,64}"))
                throw new IllegalArgumentException("Invalid citation id");
            sources = List.copyOf(sources);
            if (sources.isEmpty() || sources.size() > 8)
                throw new IllegalArgumentException("Citation needs 1 to 8 sources");
        }
    }

    public record Source(@JsonProperty("message_index") int messageIndex, @NonNull String quote) {
        public Source {
            if (quote.isBlank() || quote.length() > 4000)
                throw new IllegalArgumentException("Invalid citation quote");
        }
    }

    @JsonIgnore
    public boolean hasCalls() {
        return calls != null && !calls.isEmpty();
    }

    /** A complete program, validated by the runtime before any action executes. */
    public record Guide(@NonNull JsonNode actions) {
        public Guide {
            Objects.requireNonNull(actions, "guide.actions");
        }
    }
}
