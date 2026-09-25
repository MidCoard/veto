package top.focess.veto.agent;

import java.util.List;
import org.jspecify.annotations.NonNull;

/** Conservative recovery from recorded facts, never an execution checkpoint. */
public final class RecordRecovery {
    private RecordRecovery() {}

    /**
     * Whether the recorded history ends mid-episode (an unfinished prompt, thought, tool call or an
     * interrupted execution), so a resumed session must be told the prior work is not pending.
     */
    public static boolean requiresExplicitContinuation(@NonNull List<TurnRecord> records) {
        boolean unfinished = false;
        for (TurnRecord record : records) {
            switch (record.type()) {
                case USER_PROMPT, USER_INTERRUPT, ASSISTANT_THOUGHT, TOOL_CALL -> unfinished = true;
                case ASSISTANT_RESPONSE -> unfinished = false;
                case EXECUTION_ERROR ->
                        unfinished = "INTERRUPTED".equals(record.payload().get("outcome"));
                default -> {}
            }
        }
        return unfinished;
    }
}
