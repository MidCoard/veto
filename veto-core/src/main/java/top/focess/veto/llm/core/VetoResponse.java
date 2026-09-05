package top.focess.veto.llm.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
        String thought, List<@NonNull ToolCall> calls, String message, Guide guide) {

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
