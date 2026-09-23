package top.focess.veto.api.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** Tool-authored submissions before the runtime resolves evidence and transfers control. */
public sealed interface ResponseRequest {
    record Plan(
            @NonNull JsonNode actions,
            @NonNull ActionsProgram program,
            @NonNull PlanExecution execution)
            implements ResponseRequest {}

    record Answer(@NonNull String message, @NonNull List<Citation> citations)
            implements ResponseRequest {
        public Answer {
            citations = List.copyOf(citations);
        }
    }

    record Citation(@NonNull String id, @NonNull List<Source> sources) {
        public Citation {
            sources = List.copyOf(sources);
        }
    }

    record Source(Integer messageIndex, @NonNull String quote) {}
}
