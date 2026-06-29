package top.focess.veto.memory;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link TurnRecordEntity} — the durable per-turn audit/replay log.
 */
@Repository
public interface TurnRecordRepository extends JpaRepository<TurnRecordEntity, String> {

    /** A session's turns in order (for replay). */
    List<TurnRecordEntity> findBySessionIdOrderByTurnNumberAsc(String sessionId);
}
