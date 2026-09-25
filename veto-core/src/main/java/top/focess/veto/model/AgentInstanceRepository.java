package top.focess.veto.model;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data JPA access to {@link AgentEntity} (agent instances within sessions). */
@Repository
public interface AgentInstanceRepository extends JpaRepository<AgentEntity, String> {
    /** All agents belonging to {@code sessionId}. */
    @NonNull List<AgentEntity> findBySessionId(@NonNull String sessionId);

    /** Bulk-delete every agent belonging to {@code sessionId} (used by user-deletion cascade). */
    void deleteBySessionId(@NonNull String sessionId);
}
