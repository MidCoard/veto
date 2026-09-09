package top.focess.veto.memory;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link TurnRecordEntity} — the durable per-turn audit/replay log.
 */
@Repository
public interface TurnRecordRepository extends JpaRepository<TurnRecordEntity, String> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(
            "update TurnRecordEntity t set t.payload = :payload where t.sessionId = :sessionId and t.userId = :userId and t.agentId = :agentId and t.turnNumber = :turn")
    int updateRecordMetadata(
            @Param("sessionId") String sessionId,
            @Param("userId") String userId,
            @Param("agentId") String agentId,
            @Param("turn") int turn,
            @Param("payload") String payload);

    @Query(
            "select distinct t.agentId from TurnRecordEntity t where t.sessionId = :sessionId and t.agentId is not null")
    @NonNull List<String> findAgentIdsBySessionId(@Param("sessionId") @NonNull String sessionId);

    /** A session's turns in order (for replay). */
    @NonNull List<TurnRecordEntity> findBySessionIdOrderByTurnNumberAsc(String sessionId);

    /** Every agent stream in a session, merged by durable event time for the records UI. */
    @NonNull List<TurnRecordEntity> findBySessionIdOrderByTimestampAsc(String sessionId);

    /**
     * One agent's turn stream within a session, in order. This is the per-agent replay path: a
     * group's Leader and each Mate each own a distinct stream (filtered by {@code agent_id}); the
     * composite index {@code idx_turn_records_agent_stream} serves it without a full-table scan.
     */
    @NonNull List<TurnRecordEntity> findBySessionIdAndAgentIdOrderByTurnNumberAsc(
            String sessionId, String agentId);
}
