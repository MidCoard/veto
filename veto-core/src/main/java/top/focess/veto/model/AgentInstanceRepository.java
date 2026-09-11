package top.focess.veto.model;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentInstanceRepository extends JpaRepository<AgentEntity, String> {
    @Modifying
    @Query(
            "update AgentEntity a set a.executionWait = :reason, a.waitRequestId = :request where a.id = :agent and a.sessionId = :session")
    int updateExecutionWait(
            @Param("session") @NonNull String session,
            @Param("agent") @NonNull String agent,
            @Param("reason") String reason,
            @Param("request") String request);

    @NonNull List<AgentEntity> findBySessionId(@NonNull String sessionId);

    /** Bulk-delete every agent belonging to {@code sessionId} (used by user-deletion cascade). */
    void deleteBySessionId(@NonNull String sessionId);

    @Modifying
    @Query(
            "update AgentEntity a set a.userPaused = :paused where a.id = :agent and a.sessionId = :session")
    int updateUserPaused(
            @Param("session") @NonNull String session,
            @Param("agent") @NonNull String agent,
            @Param("paused") boolean paused);
}
