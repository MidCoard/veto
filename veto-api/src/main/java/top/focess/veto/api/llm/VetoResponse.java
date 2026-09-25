package top.focess.veto.api.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Internal adapter result: model text, native calls and resolved answer citations. Execution plans
 * are host-controlled execution directives, never model response fields. thought contains
 * provider-exposed reasoning text, not a model-authored envelope or a thinking configuration.
 *
 * @param thought optional provider-exposed reasoning text
 * @param calls native calls decoded by the provider adapter, or null when absent
 * @param message optional assistant response text
 * @param citations optional resolved citations attached by the response workflow
 */
@com.fasterxml.jackson.annotation.JsonInclude(
        com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record VetoResponse(
        String thought,
        @JsonIgnore List<@NonNull ToolCall> calls,
        String message,
        List<@NonNull Citation> citations) {

    /** Copies citation groups when present. */
    public VetoResponse {
        if (citations != null) citations = List.copyOf(citations);
    }

    /**
     * Creates an adapter response without resolved citations.
     *
     * @param thought optional provider-exposed reasoning
     * @param calls optional decoded native calls
     * @param message optional assistant text
     */
    public VetoResponse(String thought, List<@NonNull ToolCall> calls, String message) {
        this(thought, calls, message, null);
    }

    /**
     * One validated citation group.
     *
     * @param id stable response-local citation identifier
     * @param sources one to eight source excerpts supporting the citation
     */
    public record Citation(@NonNull String id, @NonNull List<@NonNull Source> sources) {
        /** Checks the citation identifier and bounds the source list. */
        public Citation {
            if (!id.matches("[A-Za-z0-9_-]{1,64}"))
                throw new IllegalArgumentException("Invalid citation id");
            sources = List.copyOf(sources);
            if (sources.isEmpty() || sources.size() > 8)
                throw new IllegalArgumentException("Citation needs 1 to 8 sources");
        }
    }

    /**
     * One source excerpt selected from compiled message history.
     *
     * @param messageIndex zero-based compiled-message index, or a workflow-defined sentinel
     * @param quote nonblank source excerpt of at most 4,000 characters
     */
    public record Source(@JsonProperty("message_index") int messageIndex, @NonNull String quote) {
        /** Rejects blank or overly long excerpts. */
        public Source {
            if (quote.isBlank() || quote.length() > 4000)
                throw new IllegalArgumentException("Invalid citation quote");
        }
    }

    /**
     * Returns whether the adapter supplied at least one native call.
     *
     * @return whether {@link #calls()} is nonempty
     */
    @JsonIgnore
    public boolean hasCalls() {
        return calls != null && !calls.isEmpty();
    }
}
