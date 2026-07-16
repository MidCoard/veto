package top.focess.veto.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A session - the conversation container a terminal/frontend attaches to. Holds one primary agent
 * (plus future sub-agents). DB-persisted so it survives restart.
 */
@Entity
@Table(name = "sessions")
public class SessionEntity {

    @Id private String id;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String name;

    @Column(name = "primary_agent_id")
    private String primaryAgentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    protected SessionEntity() {}

    public SessionEntity(@NonNull String owner, @NonNull String name) {
        this.id = UUID.randomUUID().toString();
        this.owner = owner;
        this.name = name;
        this.createdAt = Instant.now();
        this.lastActiveAt = this.createdAt;
    }

    public @NonNull String getId() {
        return id;
    }

    public @NonNull String getOwner() {
        return owner;
    }

    public @NonNull String getName() {
        return name;
    }

    public void setName(@NonNull String name) {
        this.name = name;
    }

    public @Nullable String getPrimaryAgentId() {
        return primaryAgentId;
    }

    public void setPrimaryAgentId(@Nullable String primaryAgentId) {
        this.primaryAgentId = primaryAgentId;
    }

    public @NonNull Instant getCreatedAt() {
        return createdAt;
    }

    public @Nullable Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void touch() {
        this.lastActiveAt = Instant.now();
    }
}
