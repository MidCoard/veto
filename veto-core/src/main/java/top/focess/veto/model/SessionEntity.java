package top.focess.veto.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.llm.core.ToolResultPresentationMode;

/**
 * A session - the conversation container a terminal/frontend attaches to. Holds one primary agent
 * (plus future sub-agents). DB-persisted so it survives restart.
 */
@Entity
@Table(name = "sessions")
public class SessionEntity {

    @Id private @NonNull String id = "";

    @Column(nullable = false)
    private @NonNull String owner = "";

    @Column(nullable = false)
    private @NonNull String name = "";

    /**
     * CSV of host paths backing the session's workspace (the roots the session's agents resolve
     * paths against). Nullable in the schema so existing rows survive a {@code ddl-auto=update}
     * add-column; {@link top.focess.veto.session.SessionService#createSession} enforces it
     * non-blank at creation (the "path required" contract).
     */
    @Column(name = "workspace_roots")
    private String workspaceRoots;

    @Column(name = "primary_agent_id")
    private String primaryAgentId;

    /** Immutable session-start feature selection; null legacy rows mean CONTENT_ONLY. */
    @Enumerated(EnumType.STRING)
    @Column(name = "tool_result_presentation")
    private ToolResultPresentationMode toolResultPresentation;

    @Column(name = "created_at", nullable = false)
    private @NonNull Instant createdAt = Instant.EPOCH;

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
    public SessionEntity(@NonNull String owner, @NonNull String name, String workspaceRoots) {
        this(owner, name, workspaceRoots, ToolResultPresentationMode.CONTENT_ONLY);
    }

    public SessionEntity(
            @NonNull String owner,
            @NonNull String name,
            String workspaceRoots,
            @NonNull ToolResultPresentationMode toolResultPresentation) {
        this.id = UUID.randomUUID().toString();
        this.owner = owner;
        this.name = name;
        this.workspaceRoots = workspaceRoots;
        this.toolResultPresentation = toolResultPresentation;
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

    public String getWorkspaceRoots() {
        return workspaceRoots;
    }

    public void setWorkspaceRoots(String workspaceRoots) {
        this.workspaceRoots = workspaceRoots;
    }

    public String getPrimaryAgentId() {
        return primaryAgentId;
    }

    public void setPrimaryAgentId(String primaryAgentId) {
        this.primaryAgentId = primaryAgentId;
    }

    public @NonNull ToolResultPresentationMode getToolResultPresentation() {
        ToolResultPresentationMode stored = toolResultPresentation;
        return stored != null ? stored : ToolResultPresentationMode.CONTENT_ONLY;
    }

    public @NonNull Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void touch() {
        this.lastActiveAt = Instant.now();
    }
}
