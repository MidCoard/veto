package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;

/** Notifies that one agent reached terminal execution. */
public final class AgentTerminatedEvent extends LifecycleEvent {
    private final @NonNull String sessionId;
    private final @NonNull String agentId;

    /**
     * Creates the agent-terminated notification.
     *
     * @param owner authenticated owner identity
     * @param sessionId containing session identity
     * @param agentId terminated agent identity
     */
    public AgentTerminatedEvent(
            @NonNull String owner, @NonNull String sessionId, @NonNull String agentId) {
        super(owner);
        this.sessionId = sessionId;
        this.agentId = agentId;
    }

    /**
     * Returns the containing session identity.
     *
     * @return session identity
     */
    public @NonNull String sessionId() {
        return sessionId;
    }

    /**
     * Returns the terminated agent identity.
     *
     * @return agent identity
     */
    public @NonNull String agentId() {
        return agentId;
    }
}
