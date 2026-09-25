package top.focess.veto.agent.intercept;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data store of {@link HitlRecordEntity} rows. */
public interface HitlRecordRepository extends JpaRepository<HitlRecordEntity, Long> {
    /** One session's rows for the agent, oldest first. */
    @NonNull List<HitlRecordEntity> findBySessionIdAndAgentIdOrderByIdAsc(
            @NonNull String sessionId, @NonNull String agentId);

    /** All rows for the agent across sessions, oldest first. */
    @NonNull List<HitlRecordEntity> findByAgentIdOrderByIdAsc(@NonNull String agentId);

    /** Deletes every row recorded under the session. */
    void deleteBySessionId(@NonNull String sessionId);
}
