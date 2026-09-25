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

    /**
     * A quote selector within one model-input message.
     *
     * @param messageIndex explicit message index, or {@code null} for host resolution
     * @param quote exact quoted text
     */
    record Selector(@Nullable Integer messageIndex, @NonNull String quote) {}

    /**
     * A citation declaration and its candidate source selectors.
     *
     * @param id citation identifier used by the response
     * @param sources nonempty candidate selectors
     */
    record Declaration(@NonNull String id, @NonNull List<Selector> sources) {
        /** Defensively copies the candidate selector list. */
        public Declaration {
            sources = List.copyOf(sources);
        }
    }

    /**
     * A validation problem associated with a citation declaration.
     *
     * @param id citation identifier
     * @param messageIndex implicated message index, or the host's sentinel when unavailable
     * @param status stable issue status
     */
    record Issue(@NonNull String id, int messageIndex, @NonNull String status) {}

    /**
     * Summary of a message eligible for evidence inspection.
     *
     * @param index stable index in the exact model input
     * @param role message role
     * @param toolCall whether the message represents a tool call
     */
    record Message(int index, @NonNull String role, boolean toolCall) {}

    /**
     * Validated citations and the receipt that binds them to this call.
     *
     * @param citations resolved citations
     * @param receipt host-issued receipt for those citations
     * @param issues declarations that could not be resolved
     */
    record Inspection(
            @NonNull List<VetoResponse.Citation> citations,
            @NonNull Receipt receipt,
            @NonNull List<Issue> issues) {
        /** Defensively copies the resolved citations and issue lists. */
        public Inspection {
            citations = List.copyOf(citations);
            issues = List.copyOf(issues);
        }
    }

    /**
     * Resolves and validates source declarations against the current model input.
     *
     * @param declarations citation declarations to inspect
     * @return resolved citations, receipt, and validation issues
     */
    @NonNull Inspection inspect(@NonNull List<Declaration> declarations);

    /**
     * Revalidates already-resolved citations against the current model input.
     *
     * @param declarations resolved citations to inspect
     * @return validated citations, receipt, and validation issues
     */
    @NonNull Inspection inspectResolved(@NonNull List<VetoResponse.Citation> declarations);

    /**
     * Lists the messages that may be cited by the current response.
     *
     * @return summaries of messages in the exact inspectable model input
     */
    @NonNull List<Message> messages();
}
