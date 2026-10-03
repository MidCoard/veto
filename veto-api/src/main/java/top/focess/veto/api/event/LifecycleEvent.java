package top.focess.veto.api.event;

/**
 * Base for user authentication, logout, session deletion, and agent termination notifications.
 *
 * <p>The host delivers these facts at the transition documented by their concrete type; logout is
 * announced when it begins. Recipients follow the host's current invocation context, not the
 * event's class or payload scope. Observing a fact grants no authority. Ordinary handler failures
 * are logged and contained so eligible remaining handlers continue; fatal VM failures and thread
 * death propagate. These facts carry no cooperative host stop signal.
 *
 * <p>Delivery is synchronous on the transition's caller, including a transaction callback for
 * committed deletion. Separate transitions may invoke the same listener concurrently. The event
 * remains confined to its dispatch as described by {@link Event}; no ordered lifecycle queue is
 * provided.
 */
public abstract class LifecycleEvent extends Event {
    /** Creates a non-cancellable lifecycle notification. */
    protected LifecycleEvent() {}
}
