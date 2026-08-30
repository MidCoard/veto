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
 * The raw-turn write-through log. Called from the {@code AgentRunner} after each turn is appended;
 * persists the turn to {@link TurnRecordRepository} (the durable audit/replay log — session resume
 * after restart, Leader reconstruction, audit). Turn persistence is session state, not memory:
 * nothing here feeds LTM (long-term memory is agent-written only, via {@code write_memory}).
 *
 * <p>Logging is silent/background and never blocks the loop — a failure is logged and swallowed so
 * the agent loop is unaffected. The repository may be absent (tests / deployments without
 * durability); logging is then a no-op. The {@code veto.memory.capture.enabled} flag (default
 * {@code true}) controls whether turns are logged.
 */
@Component
public class TurnLogService {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.memory.TurnLogService");

    private final @NonNull ObjectMapper mapper;
    private final TurnRecordRepository turnRecordRepository;
    private volatile boolean enabled = true;

    @Autowired
    public TurnLogService(
            @Autowired(required = false) TurnRecordRepository turnRecordRepository,
            @NonNull ObjectMapper mapper) {
        this.turnRecordRepository = turnRecordRepository;
        this.mapper = mapper;
    }

    /** Disable logging (e.g. for tests that want a clean repository). */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Persist one turn to the raw-turn log. No-op for non-loggable turn types and when disabled.
     * Best-effort — a DB failure never breaks the loop.
     */
    public void log(
            @NonNull TurnRecord turn,
            @NonNull UUID sessionId,
            @NonNull UUID userId,
            String agentId) {
        if (!enabled) {
            return;
        }
        if (!isLoggable(turn.type())) {
            return;
        }
        if (turnRecordRepository == null) {
            return;
        }
        try {
            turnRecordRepository.save(
                    TurnRecordEntity.of(turn, sessionId, userId, agentId, mapper));
        } catch (RuntimeException e) {
            log.warn("TurnLogService: raw-turn log failed (turn {})", turn.turnNumber(), e);
        }
    }

    /**
     * Every turn type is loggable, including the compiler directives (REWIND, AGENT_INIT,
     * COMPACTION_SUMMARY): they are part of the durable raw history the loader replays, and
     * dropping them on persist would corrupt the compiled view on resume (a rewound session would
     * replay its pre-rewind turns; a transformed Leader would lose its AGENT_INIT anchor).
     */
    private static boolean isLoggable(@NonNull TurnType type) {
        return true;
    }
}
