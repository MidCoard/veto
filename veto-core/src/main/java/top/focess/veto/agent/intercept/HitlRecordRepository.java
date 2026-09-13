package top.focess.veto.agent.intercept;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HitlRecordRepository extends JpaRepository<HitlRecordEntity, Long> {
    @NonNull List<HitlRecordEntity> findBySessionIdAndAgentIdOrderByIdAsc(
            @NonNull String sessionId, @NonNull String agentId);

    @NonNull List<HitlRecordEntity> findByAgentIdOrderByIdAsc(@NonNull String agentId);

    void deleteBySessionId(@NonNull String sessionId);
}
