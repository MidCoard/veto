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

    /**
     * CSV of host paths backing the session's workspace (the roots the session's agents resolve
     * paths against). Nullable in the schema so existing rows survive a {@code ddl-auto=update}
     * add-column; {@link top.focess.veto.session.SessionService#createSession} enforces it
     * non-blank at creation (the "path required" contract).
     */
    @Column(name = "workspace_roots")
    private @Nullable String workspaceRoots;

    @Column(name = "primary_agent_id")
    private String primaryAgentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    protected SessionEntity() {}

    public SessionEntity(@NonNull String owner, @NonNull String name) {
        this(owner, name, null);
    }

    /**
     * @param owner the session owner
     * @param name the session name
     * @param workspaceRoots CSV of host paths backing the session's workspace; null/blank falls
     *     back to the JVM working dir at activation (see {@link
     *     top.focess.veto.agent.AgentService})
     */
    public SessionEntity(
            @NonNull String owner, @NonNull String name, @Nullable String workspaceRoots) {
        this.id = UUID.randomUUID().toString();
        this.owner = owner;
        this.name = name;
        this.workspaceRoots = workspaceRoots;
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

    public @Nullable String getWorkspaceRoots() {
        return workspaceRoots;
    }

    public void setWorkspaceRoots(@Nullable String workspaceRoots) {
        this.workspaceRoots = workspaceRoots;
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
