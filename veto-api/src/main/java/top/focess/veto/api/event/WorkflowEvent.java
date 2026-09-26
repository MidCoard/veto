package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.Cancellation;

/**
 * Base for session-scoped workflow events dispatched on the agent's workflow thread.
 *
 * <p>Carries the workflow identity and the cooperative cancellation signal. Subclasses add the
 * mutable payload their handlers transform. Observing an event never grants authority, and a
 * handler cannot relax a decision another handler already tightened.
 */
public abstract class WorkflowEvent extends Event {
    private final String owner;
    private final @NonNull String sessionId;
    private final @NonNull String agentId;
    private final @NonNull Cancellation cancellation;

    /**
     * Creates the workflow event identity.
     *
     * @param owner authenticated owner, or {@code null} when unavailable for the phase
     * @param sessionId current session identity
     * @param agentId current agent identity
     * @param cancellation cooperative cancellation signal
     */
    protected WorkflowEvent(
            String owner,
            @NonNull String sessionId,
            @NonNull String agentId,
            @NonNull Cancellation cancellation) {
        this.owner = owner;
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.cancellation = cancellation;
    }

    /**
     * Returns the authenticated owner.
     *
     * @return owner identity, or {@code null} when unavailable for the phase
     */
    public String owner() {
        return owner;
    }

    /**
     * Returns the current session identity.
     *
     * @return session identity
     */
    public @NonNull String sessionId() {
        return sessionId;
    }

    /**
     * Returns the current agent identity.
     *
     * @return agent identity
     */
    public @NonNull String agentId() {
        return agentId;
    }

    /**
     * Returns the cooperative cancellation signal.
     *
     * @return cancellation signal
     */
    public @NonNull Cancellation cancellation() {
        return cancellation;
    }
}
