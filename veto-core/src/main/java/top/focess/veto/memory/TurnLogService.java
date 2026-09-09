package top.focess.veto.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.bus.DeltaFrame;

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
    private DeltaBroker deltaBroker;

    @Autowired(required = false)
    public void setDeltaBroker(@NonNull DeltaBroker deltaBroker) {
        this.deltaBroker = deltaBroker;
    }

    private void notifyChanged(@NonNull UUID sessionId, int turnNumber) {
        DeltaBroker broker = deltaBroker;
        if (broker == null) return;
        Runnable publish =
                () -> {
                    try {
                        broker.publish(
                                DeltaFrame.builder()
                                        .sessionId(sessionId)
                                        .kind(DeltaFrame.Kind.RECORD_UPDATED)
                                        .attr("turnNumber", turnNumber)
                                        .build());
                    } catch (RuntimeException error) {
                        log.warn("Could not publish committed record update", error);
                    }
                };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            publish.run();
                        }
                    });
        } else {
            publish.run();
        }
    }

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

    /** Enrich an existing record after provider usage arrives; never creates a history event. */
    public void updateMetadata(
            @NonNull TurnRecord turn,
            @NonNull UUID sessionId,
            @NonNull UUID userId,
            @NonNull String agentId) {
        if (!enabled || turnRecordRepository == null) return;
        try {
            int changed =
                    turnRecordRepository.updateRecordMetadata(
                            sessionId.toString(),
                            userId.toString(),
                            agentId,
                            turn.turnNumber(),
                            mapper.writeValueAsString(turn.payload()));
            if (changed > 0) notifyChanged(sessionId, turn.turnNumber());
        } catch (Exception e) {
            log.warn("Could not persist record usage for turn {}", turn.turnNumber(), e);
        }
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
            notifyChanged(sessionId, turn.turnNumber());
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
        if (!enabled || turnRecordRepository == null) {
            throw new IllegalStateException("Durable turn logging is unavailable");
        }
        turnRecordRepository.save(TurnRecordEntity.of(turn, sessionId, userId, agentId, mapper));
        notifyChanged(sessionId, turn.turnNumber());
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
