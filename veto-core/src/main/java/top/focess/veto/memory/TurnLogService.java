package top.focess.veto.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.bus.SessionActions;

/**
 * The raw-turn write-through log. Called from the {@code AgentRunner} after each turn is appended;
 * persists the turn to {@link TurnRecordRepository} (the durable audit/replay log — session resume
 * after restart, Leader reconstruction, audit). Turn persistence is session state, not memory:
 * nothing here feeds LTM (long-term memory is agent-written only, via {@code write_memory}).
 *
 * <p>Logging is silent/background and never blocks the loop — a failure is logged and swallowed so
 * the agent loop is unaffected. Turn persistence is a required dependency; logging can be disabled
 * explicitly through {@link #setEnabled(boolean)}.
 */
@Component
public class TurnLogService {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.memory.TurnLogService");

    private final @NonNull ObjectMapper mapper;
    private final @NonNull TurnRecordRepository turnRecordRepository;
    private final @NonNull SessionActions actions;
    private volatile boolean enabled = true;

    /** Creates the durable turn log. Records are read through the records API. */
    public TurnLogService(
            @NonNull TurnRecordRepository turnRecordRepository,
            @NonNull ObjectMapper mapper,
            @NonNull SessionActions actions) {
        this.turnRecordRepository = turnRecordRepository;
        this.mapper = mapper;
        this.actions = actions;
    }

    /** Enables or disables turn logging. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Enrich an existing record after provider usage arrives; never creates a history event. */
    public void updateMetadata(
            @NonNull TurnRecord turn,
            @NonNull UUID sessionId,
            @NonNull UUID userId,
            @NonNull String agentId) {
        if (!enabled) return;
        try {
            int changed =
                    turnRecordRepository.updateRecordMetadata(
                            sessionId.toString(),
                            userId,
                            agentId,
                            turn.turnNumber(),
                            mapper.writeValueAsString(turn.payload()),
                            mapper.writeValueAsString(turn.llmUsage()));
            if (changed > 0) actions.changed(sessionId, "records", "history");
            log.debug("Updated metadata for {} persisted records", changed);
        } catch (Exception e) {
            log.warn("Could not persist record usage for turn {}", turn.turnNumber(), e);
        }
    }

    /**
     * Persist every turn type to the raw-turn log, including compiler directives needed for replay.
     * No-op when disabled. Best-effort — a DB failure never breaks the loop.
     */
    public void log(
            @NonNull TurnRecord turn,
            @NonNull UUID sessionId,
            @NonNull UUID userId,
            String agentId) {
        if (!enabled) {
            return;
        }
        try {
            turnRecordRepository.save(
                    TurnRecordEntity.of(turn, sessionId, userId, agentId, mapper));
            actions.changed(sessionId, "records", "history");
        } catch (RuntimeException e) {
            log.warn("TurnLogService: raw-turn log failed (turn {})", turn.turnNumber(), e);
        }
    }

    /** Notifications must be durable before their source queue is acknowledged. */
    public void logRequired(
            @NonNull TurnRecord turn,
            @NonNull UUID sessionId,
            @NonNull UUID userId,
            @NonNull String agentId) {
        if (!enabled) {
            throw new IllegalStateException("Durable turn logging is unavailable");
        }
        turnRecordRepository.save(TurnRecordEntity.of(turn, sessionId, userId, agentId, mapper));
        actions.changed(sessionId, "records", "history");
    }
}
