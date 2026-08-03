package top.focess.veto.model;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentInstanceRepository extends JpaRepository<AgentEntity, String> {

    @NonNull List<AgentEntity> findBySessionId(@NonNull String sessionId);

    /** Bulk-delete every agent belonging to {@code sessionId} (used by user-deletion cascade). */
    void deleteBySessionId(@NonNull String sessionId);
}
