package top.focess.veto.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.memory.TurnLogService;

/** Owns synchronized history snapshots, durable numbering and turn persistence. */
final class AgentHistory {
    private static final Logger log = LoggerFactory.getLogger("top.focess.veto.agent.AgentHistory");
    private final @NonNull List<TurnRecord> history = new ArrayList<>();
    private final TurnLogService turnLogService;
    private final @NonNull Supplier<UUID> session;
    private final @NonNull UUID userId;
    private final @NonNull String agentId;

    AgentHistory(
            TurnLogService log,
            @NonNull Supplier<UUID> session,
            @NonNull UUID userId,
            @NonNull String agentId) {
        this.turnLogService = log;
        this.session = session;
        this.userId = userId;
        this.agentId = agentId;
    }

    synchronized @NonNull List<TurnRecord> snapshot() {
        return List.copyOf(history);
    }

    synchronized int turnNumber() {
        return history.isEmpty() ? 0 : history.getLast().turnNumber();
    }

    synchronized int seed(@NonNull List<TurnRecord> replayed) {
        if (history.isEmpty()) history.addAll(replayed);
        return history.stream().mapToInt(TurnRecord::turnNumber).max().orElse(0);
    }

    @NonNull TurnRecord append(@NonNull TurnRecord turn, boolean required) {
        @NonNull TurnRecord numbered;
        synchronized (this) {
            // turn_number is the durable unique key (uk_turn_records_agent_turn on
            // session_id, agent_id, turn_number). The in-memory history's high-water mark is the
            // allocation authority, not the turnNumber counter: a caller's ++turnNumber
            // side-effect leaves the counter equal to the passed number whether the caller
            // incremented or forgot, so the counter cannot distinguish a correct advance from a
            // reuse. If the passed number does not advance past the last recorded turn, allocate
            // the next one so a duplicate is never persisted (the DB would reject it and leave the
            // durable log inconsistent with the in-memory history). history only grows, so its
            // last element carries the max turn_number.
            int highWater = history.isEmpty() ? 0 : history.getLast().turnNumber();
            numbered = turn.turnNumber() <= highWater ? turn.withTurnNumber(highWater + 1) : turn;
            if (required && turnLogService != null) {
                turnLogService.logRequired(numbered, session.get(), userId, agentId);
            }
            history.add(numbered);
        }
        // Persist the turn to the raw-turn audit/replay log (session resume, Leader
        // reconstruction). Best-effort — done outside the history lock so a DB write doesn't
        // block history readers, and the service swallows failures so the loop is never affected.
        if (turnLogService != null && !required) {
            try {
                turnLogService.log(numbered, session.get(), userId, agentId);
            } catch (RuntimeException e) {
                log.warn("Agent {} turn log failed", agentId, e);
            }
        }
        return numbered;
    }

    void recordUsage(int throughTurn, @NonNull UsageMeasurement measurement) {
        TurnRecord updated = null;
        synchronized (this) {
            for (int i = history.size() - 1; i >= 0; i--) {
                @NonNull TurnRecord candidate = history.get(i);
                if (candidate.turnNumber() <= throughTurn
                        && candidate.type() != TurnType.TOKEN_USAGE) {
                    updated = RecordUsage.add(candidate, measurement);
                    history.set(i, updated);
                    break;
                }
            }
        }
        if (updated == null) return;
        if (turnLogService != null)
            turnLogService.updateMetadata(updated, session.get(), userId, agentId);
    }
}
