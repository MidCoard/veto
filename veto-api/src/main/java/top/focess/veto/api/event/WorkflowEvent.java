package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.Cancellation;

/**
 * Base for session-scoped workflow events dispatched synchronously on the producer's thread.
 *
 * <p>Carries the workflow identity and the cooperative cancellation signal. Subclasses add the
 * mutable payload their handlers transform. Observing an event never grants authority, and a
 * handler cannot relax a decision another handler already tightened.
 *
 * <p>The caller may be an agent worker, an input-submitting thread, or an admitted tool caller.
 * Handlers complete before the producer reads the transformed event; no event executor or
 * cross-dispatch serialization is implied. The confinement contract of {@link Event} applies.
 */
public abstract class WorkflowEvent extends Event {
    private final Scope.@NonNull AgentScope scope;
    private final @NonNull Cancellation cancellation;

    /**
     * Creates a session-selected, fail-closed workflow event.
     *
     * @param scope authenticated owner, session and agent identity
     * @param cancellation cooperative cancellation signal
     */
    protected WorkflowEvent(Scope.@NonNull AgentScope scope, @NonNull Cancellation cancellation) {
        super(Recipients.SESSION_PLUGINS, FailurePolicy.FAIL_CLOSED);
        this.scope = scope;
        this.cancellation = cancellation;
    }

    /**
     * Returns the authenticated owner, session and agent identity.
     *
     * @return workflow agent scope
     */
    @Override
    public Scope.@NonNull AgentScope scope() {
        return scope;
    }

    /**
     * Returns the cooperative cancellation signal.
     *
     * @return workflow cancellation signal
     */
    @Override
    public @NonNull Cancellation cancellation() {
        return cancellation;
    }
}
