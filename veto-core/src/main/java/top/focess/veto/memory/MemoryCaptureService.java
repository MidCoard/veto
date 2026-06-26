package top.focess.veto.memory;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;

/**
 * The continuous memory-capture service (long_term_memory_tiers.md §3.1). Wires the capture points
 * (tool-result, thought, user-prompt) into the {@link MemoryStore}. Called from the {@code
 * AgentRunner} after a tool executes, after a thought is recorded, or after a user prompt is
 * appended. Capture is silent/background and never blocks the loop.
 *
 * <p>Capture is opt-in per deployment: the {@code veto.memory.capture.enabled} flag (default {@code
 * true}) controls whether turns are written to Session LTM. A deployment that wants capture off
 * (e.g. a test environment) sets the flag to {@code false}.
 */
@Component
public class MemoryCaptureService {

    private final MemoryStore store;
    private volatile boolean enabled = true;

    @Autowired
    public MemoryCaptureService(MemoryStore store) {
        this.store = store;
    }

    /** Disable capture (e.g. for tests that want a clean store). */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Capture one turn into Session LTM. The agent's loop calls this at each capture point (after a
     * TOOL_RESPONSE, after an ASSISTANT_THOUGHT, after a USER_PROMPT). No-op for other turn types
     * and when disabled.
     */
    public void capture(TurnRecord turn, UUID sessionId, UUID userId) {
        if (!enabled) {
            return;
        }
        if (turn == null || sessionId == null || userId == null) {
            return;
        }
        if (!isCapturable(turn.type())) {
            return;
        }
        store.capture(turn, sessionId, userId);
    }

    private static boolean isCapturable(TurnType type) {
        return type == TurnType.TOOL_RESPONSE
                || type == TurnType.ASSISTANT_THOUGHT
                || type == TurnType.USER_PROMPT
                || type == TurnType.ASSISTANT_RESPONSE
                || type == TurnType.USER_INTERRUPT;
    }
}
