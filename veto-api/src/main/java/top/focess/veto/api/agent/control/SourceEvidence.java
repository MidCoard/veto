package top.focess.veto.api.agent.control;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.agent.workflow.PluginWork;
import top.focess.veto.api.llm.VetoResponse;

/** Read-only evidence from the exact model input; the host issues and validates receipts. */
public interface SourceEvidence {
    /**
     * Only values issued by the host are accepted; implementing this interface grants no authority.
     */
    interface Receipt extends PluginWork.Source {}

    record Selector(@Nullable Integer messageIndex, @NonNull String quote) {}

    record Declaration(@NonNull String id, @NonNull List<Selector> sources) {
        public Declaration {
            sources = List.copyOf(sources);
        }
    }

    record Issue(@NonNull String id, int messageIndex, @NonNull String status) {}

    record Message(int index, @NonNull String role, boolean toolCall) {}

    record Inspection(
            @NonNull List<VetoResponse.Citation> citations,
            @NonNull Receipt receipt,
            @NonNull List<Issue> issues) {
        public Inspection {
            citations = List.copyOf(citations);
            issues = List.copyOf(issues);
        }
    }

    @NonNull Inspection inspect(@NonNull List<Declaration> declarations);

    @NonNull Inspection inspectResolved(@NonNull List<VetoResponse.Citation> declarations);

    @NonNull List<Message> messages();
}
