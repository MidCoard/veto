package top.focess.veto.agent;

import java.time.Instant;
import java.util.Map;

/**
 * One step in the ReAct loop — a thought, an optional tool call, and the observation that came
 * back.
 */
public record TurnRecord(
        int turnNumber,
        String thought,
        String callToolName,
        Map<String, Object> callArgs,
        String observation,
        Instant timestamp) {

    public TurnRecord {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}
