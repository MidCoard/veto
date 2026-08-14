package top.focess.veto.memory;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link TurnRecordEntity} — the durable per-turn audit/replay log.
 */
@Repository
public interface TurnRecordRepository extends JpaRepository<TurnRecordEntity, String> {

    /** A session's turns in order (for replay). */
    @NonNull List<TurnRecordEntity> findBySessionIdOrderByTurnNumberAsc(String sessionId);

    /**
     * One agent's turn stream within a session, in order. This is the per-agent replay path: a
     * group's Leader and each Mate each own a distinct stream (filtered by {@code agent_id}); the
     * composite index {@code idx_turn_records_agent_stream} serves it without a full-table scan.
     */
    @NonNull List<TurnRecordEntity> findBySessionIdAndAgentIdOrderByTurnNumberAsc(
            String sessionId, String agentId);
}
