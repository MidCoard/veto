package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.Cancellation;

/**
 * Base type for every host-dispatched plugin event.
 *
 * <p>An event's flags and any mutable payload belong to one synchronous dispatch. Handlers run
 * serially on the producer's calling thread; the producer reads the result once dispatch returns.
 * The {@code prevent} flag irreversibly skips later handlers by default, including supertype
 * handlers. A handler that opts in to seeing prevented events cannot clear that flag. Prevention
 * controls propagation only; it does not cancel the producer's action. Events may separately opt
 * into {@link Cancellable}, whose final reversible flag the producer reads after delivery. Security
 * decisions such as tool rejection remain distinct from both propagation and action cancellation.
 *
 * <p>The host resolves recipients from its current invocation context: a session selects that
 * session's available plugin owners; no session uses active plugin owners. Event payload identity
 * does not choose recipients. Ordinary handler and admission failures are logged and contained so
 * eligible remaining handlers continue; fatal VM failures and thread death propagate. Cooperative
 * host cancellation remains separate from plugin failure containment.
 *
 * <p>This type is not thread-safe. Handlers must not retain an event for asynchronous mutation or
 * share it across concurrent dispatches. Distinct events may be dispatched concurrently to the same
 * listener; the host does not serialize that listener's separate invocations.
 */
public abstract class Event {
    private boolean prevent;

    /** Creates the base state for one synchronous host event delivery. */
    protected Event() {}

    /**
     * Returns the event identity; observing it grants no authority.
     *
     * @return typed identity relevant to this event
     */
    public abstract @NonNull Scope scope();

    /**
     * Returns the read-only cooperative host stop signal, or null when none applies. This signal is
     * independent of mutable per-event action cancellation through {@link Cancellable}.
     *
     * @return cooperative cancellation signal, or null
     */
    public Cancellation cancellation() {
        return null;
    }

    /**
     * Reports whether the event has been irreversibly prevented.
     *
     * @return {@code true} once {@link #prevent()} has been called
     */
    public boolean isPrevent() {
        return prevent;
    }

    /**
     * Prevents the event when {@code prevent} is true. Passing false never clears a previous
     * prevention. Handlers should prefer {@link #prevent()}.
     *
     * @param prevent whether to prevent the event
     */
    public void setPrevent(boolean prevent) {
        if (prevent) this.prevent = true;
    }

    /** Irreversibly prevents the event; later handlers are skipped by default. */
    public void prevent() {
        this.prevent = true;
    }
}
