package top.focess.veto.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.AgentService;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.PluginBinding;
import top.focess.veto.integration.plugins.PluginBindingsConverter;
import top.focess.veto.llm.core.ToolResultPresentationModeConverter;
import top.focess.veto.session.SessionService;

/**
 * A session - the conversation container a terminal/frontend attaches to. Holds a primary agent and
 * the other agents that participate in the session. DB-persisted so it survives restart.
 */
@Entity
@Table(name = "sessions")
public class SessionEntity {

    @Convert(converter = PluginBindingsConverter.class)
    @Column(name = "plugin_bindings", columnDefinition = "TEXT")
    private List<PluginBinding> pluginBindings;

    public List<PluginBinding> getPluginBindings() {
        return pluginBindings;
    }

    /**
     * Set the session's plugin bindings once; a session's plugin selection is immutable after the
     * first set.
     *
     * @throws IllegalStateException if bindings were already set
     */
    public void setPluginBindings(@NonNull List<PluginBinding> value) {
        if (pluginBindings != null)
            throw new IllegalStateException("Session plugins are immutable");
        pluginBindings = List.copyOf(value);
    }

    @Id private @NonNull String id = "";

    @Column(nullable = false)
    private @NonNull String owner = "";

    @Column(nullable = false)
    private @NonNull String name = "";

    /**
     * CSV of host paths backing the session's workspace (the roots the session's agents resolve
     * paths against). Nullable in the schema so existing rows survive a {@code ddl-auto=update}
     * add-column; {@link SessionService#createSession} enforces it non-blank at creation (the "path
     * required" contract).
     */
    @Column(name = "workspace_roots")
    private String workspaceRoots;

    /** Index of the root used for relative paths and process execution. Null legacy rows mean 0. */
    @Column(name = "current_workspace_root_index")
    private Integer currentWorkspaceRootIndex;

    @Column(name = "primary_agent_id")
    private String primaryAgentId;

    /** Immutable session-start feature selection; null legacy rows mean BASIC. */
    @Convert(converter = ToolResultPresentationModeConverter.class)
    @Column(name = "tool_result_presentation")
    private ToolResultPresentationMode toolResultPresentation;

    @Column(name = "created_at", nullable = false)
    private @NonNull Instant createdAt = Instant.EPOCH;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    /** JPA no-arg constructor. */
    protected SessionEntity() {}

    /** Create a session with no workspace roots (falls back to the JVM working dir on use). */
    public SessionEntity(@NonNull String owner, @NonNull String name) {
        this(owner, name, null);
    }

    /**
     * @param owner the session owner
     * @param name the session name
     * @param workspaceRoots CSV of host paths backing the session's workspace; null/blank falls
     *     back to the JVM working dir at activation (see {@link AgentService})
     */
    public SessionEntity(@NonNull String owner, @NonNull String name, String workspaceRoots) {
        this(owner, name, workspaceRoots, ToolResultPresentationMode.BASIC);
    }

    /**
     * @param owner the session owner
     * @param name the session name
     * @param workspaceRoots CSV of host paths backing the session's workspace
     * @param toolResultPresentation the session-start tool-result presentation mode
     */
    public SessionEntity(
            @NonNull String owner,
            @NonNull String name,
            String workspaceRoots,
            @NonNull ToolResultPresentationMode toolResultPresentation) {
        this(owner, name, workspaceRoots, 0, toolResultPresentation);
    }

    /**
     * Canonical full constructor. The presentation mode is stored in canonical form and the session
     * starts with {@code lastActiveAt} equal to its creation time.
     *
     * @param currentWorkspaceRootIndex index of the root used for relative paths and execution
     */
    public SessionEntity(
            @NonNull String owner,
            @NonNull String name,
            String workspaceRoots,
            int currentWorkspaceRootIndex,
            @NonNull ToolResultPresentationMode toolResultPresentation) {
        this.id = UUID.randomUUID().toString();
        this.owner = owner;
        this.name = name;
        this.workspaceRoots = workspaceRoots;
        this.currentWorkspaceRootIndex = currentWorkspaceRootIndex;
        this.toolResultPresentation = toolResultPresentation.canonical();
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

    /** Index of the workspace root used for relative paths; legacy null rows mean index 0. */
    public int getCurrentWorkspaceRootIndex() {
        return currentWorkspaceRootIndex == null ? 0 : currentWorkspaceRootIndex;
    }

    public String getPrimaryAgentId() {
        return primaryAgentId;
    }

    public void setPrimaryAgentId(String primaryAgentId) {
        this.primaryAgentId = primaryAgentId;
    }

    /** The session-start presentation mode, canonicalized; legacy null rows mean BASIC. */
    public @NonNull ToolResultPresentationMode getToolResultPresentation() {
        return ToolResultPresentationMode.canonicalize(toolResultPresentation);
    }

    public @NonNull Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    /** Refresh {@code lastActiveAt} to now. */
    public void touch() {
        this.lastActiveAt = Instant.now();
    }
}
