package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;

/**
 * Base for best-effort user authentication, logout, session deletion, and agent termination
 * notifications.
 *
 * <p>The host broadcasts a lifecycle event to matching handlers of active plugins' contributed
 * {@link Listener}s at the transition documented by its concrete type; logout is announced when it
 * begins. Unlike {@link WorkflowEvent}, it carries no cancellation signal and is never
 * session-selected: observing one grants no authority, and a handler that fails is logged and
 * skipped so it cannot break logout, session deletion, or agent termination. Fatal VM errors and
 * thread death still propagate.
 *
 * <p>Delivery is synchronous on the transition's caller, including a transaction callback for
 * committed deletion. Separate transitions may invoke the same listener concurrently. The event
 * remains confined to its dispatch as described by {@link Event}; no ordered lifecycle queue is
 * provided.
 */
public abstract class LifecycleEvent extends Event {
    /** Creates a non-cancellable lifecycle notification. */
    protected LifecycleEvent() {}

    /**
     * Returns the event's typed identity; observing it grants no authority.
     *
     * @return the owner, session, or agent identity relevant to this event
     */
    public abstract @NonNull Scope scope();
}
