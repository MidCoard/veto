package top.focess.veto.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;

/**
 * The continuous memory-capture service (long_term_memory_tiers.md §3.1). Wires the capture points
 * (tool-result, thought, user-prompt) into the {@link MemoryStore} (semantic Session LTM) AND the
 * durable raw-turn log ({@link TurnRecordRepository}, for audit/replay). Called from the {@code
 * AgentRunner} after each turn is appended. Capture is silent/background and never blocks the loop
 * — a failure in either sink is logged and swallowed so the agent loop is unaffected.
 *
 * <p>Capture is opt-in per deployment: the {@code veto.memory.capture.enabled} flag (default {@code
 * true}) controls whether turns are captured. A deployment that wants capture off (e.g. a test
 * environment) sets the flag to {@code false}.
 */
@Component
public class MemoryCaptureService {

    private static final Logger log = LoggerFactory.getLogger(MemoryCaptureService.class);

    private final @NonNull MemoryStore store;
    private final @NonNull TurnRecordRepository turnRecordRepository;
    private final @NonNull ObjectMapper mapper;
    private volatile boolean enabled = true;

    @Autowired
    public MemoryCaptureService(
            MemoryStore store,
            @Autowired(required = false) TurnRecordRepository turnRecordRepository,
            ObjectMapper mapper) {
        this.store = store;
        this.turnRecordRepository = turnRecordRepository;
        this.mapper = mapper;
    }

    /** Disable capture (e.g. for tests that want a clean store). */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Capture one turn: into the semantic Session LTM ({@link MemoryStore}) and the raw-turn log
     * ({@link TurnRecordRepository}). The agent's loop calls this at each capture point. No-op for
     * non-capturable turn types and when disabled. Each sink is best-effort — a failure in one
     * never blocks the loop or the other.
     */
    public void capture(@NonNull TurnRecord turn, @NonNull UUID sessionId, @NonNull UUID userId) {
        if (!enabled) {
            return;
        }
        if (turn == null || sessionId == null || userId == null) {
            return;
        }
        if (!isCapturable(turn.type())) {
            return;
        }
        // (a) semantic Session LTM.
        try {
            store.capture(turn, sessionId, userId);
        } catch (RuntimeException e) {
            log.warn(
                    "MemoryCaptureService: semantic LTM capture failed (turn {})",
                    turn.turnNumber(),
                    e);
        }
        // (b) raw-turn audit/replay log. Best-effort — a DB failure must never break the loop.
        if (turnRecordRepository != null) {
            try {
                turnRecordRepository.save(TurnRecordEntity.of(turn, sessionId, userId, mapper));
            } catch (RuntimeException e) {
                log.warn(
                        "MemoryCaptureService: raw-turn log failed (turn {})",
                        turn.turnNumber(),
                        e);
            }
        }
    }

    private static boolean isCapturable(TurnType type) {
        return type == TurnType.TOOL_RESPONSE
                || type == TurnType.TOOL_CALL
                || type == TurnType.ASSISTANT_THOUGHT
                || type == TurnType.USER_PROMPT
                || type == TurnType.ASSISTANT_RESPONSE
                || type == TurnType.USER_INTERRUPT;
    }
}
