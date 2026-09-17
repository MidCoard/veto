package top.focess.veto.llm.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Internal adapter result: model text, native calls and resolved answer citations. Execution plans
 * are control directives created by submit_plan, never model response fields. thought is legacy
 * operational text, not a provider thinking configuration.
 */
@com.fasterxml.jackson.annotation.JsonInclude(
        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record VetoResponse(
        String thought,
        @JsonIgnore List<@NonNull ToolCall> calls,
        String message,
        List<@NonNull Citation> citations) {

    public VetoResponse {
        if (citations != null) citations = List.copyOf(citations);
    }

    public VetoResponse(String thought, List<@NonNull ToolCall> calls, String message) {
        this(thought, calls, message, null);
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
}
