package top.focess.veto.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.contract.EventFrame;

/**
 * The raw-turn write-through log. Called from the {@code AgentRunner} after each turn is appended;
 * persists the turn to {@link TurnRecordRepository} (the durable audit/replay log — session resume
 * after restart, Leader reconstruction, audit). Turn persistence is session state, not memory:
 * nothing here feeds LTM (long-term memory is agent-written only, via {@code write_memory}).
 *
 * <p>Logging is silent/background and never blocks the loop — a failure is logged and swallowed so
 * the agent loop is unaffected. Turn persistence and committed-update transport are required
 * dependencies; logging can be disabled explicitly through {@link #setEnabled(boolean)}.
 */
@Component
public class TurnLogService {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.memory.TurnLogService");

    private final @NonNull ObjectMapper mapper;
    private final @NonNull TurnRecordRepository turnRecordRepository;
    private volatile boolean enabled = true;
    private final @NonNull DeltaBroker deltaBroker;

    private void notifyChanged(@NonNull UUID sessionId, int turnNumber) {
        Runnable publish =
                () -> {
                    try {
                        deltaBroker.publish(
                                EventFrame.builder()
                                        .sessionId(sessionId)
                                        .kind(EventFrame.Kind.RECORD_UPDATED)
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

    /** Creates the durable turn log and its committed-update transport. */
    public TurnLogService(
            @NonNull TurnRecordRepository turnRecordRepository,
            @NonNull ObjectMapper mapper,
            @NonNull DeltaBroker deltaBroker) {
        this.turnRecordRepository = turnRecordRepository;
        this.mapper = mapper;
        this.deltaBroker = deltaBroker;
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
            if (changed > 0) notifyChanged(sessionId, turn.turnNumber());
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
        if (!enabled) {
            throw new IllegalStateException("Durable turn logging is unavailable");
        }
        turnRecordRepository.save(TurnRecordEntity.of(turn, sessionId, userId, agentId, mapper));
        notifyChanged(sessionId, turn.turnNumber());
    }
}
