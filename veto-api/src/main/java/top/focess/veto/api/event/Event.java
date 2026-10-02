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
 * into {@link Cancellable}, whose final reversible flag the producer reads after delivery.
 * Security decisions such as tool rejection remain distinct from both propagation and action
 * cancellation.
 *
 * <p>This type is not thread-safe. Handlers must not retain an event for asynchronous mutation or
 * share it across concurrent dispatches. Distinct events may be dispatched concurrently to the same
 * listener; the host does not serialize that listener's separate invocations.
 */
public abstract class Event {
    /** Recipients resolved independently of the event's payload hierarchy. */
    public enum Recipients {
        /** Every active plugin contributing a matching handler. */
        ACTIVE_PLUGINS,
        /** Matching handlers from the event scope's selected session plugins. */
        SESSION_PLUGINS
    }

    /** Treatment of nonfatal handler failures during this event's delivery. */
    public enum FailurePolicy {
        /** Stop delivery with a sanitized failure. */
        FAIL_CLOSED,
        /** Continue delivery after reporting the failure. */
        CONTINUE
    }

    private final @NonNull Recipients recipients;
    private final @NonNull FailurePolicy failurePolicy;
    private boolean prevent;

    /**
     * Creates an event with immutable delivery policies, independent of its payload hierarchy.
     *
     * @param recipients plugin recipients to resolve
     * @param failurePolicy treatment of nonfatal handler failures
     */
    protected Event(@NonNull Recipients recipients, @NonNull FailurePolicy failurePolicy) {
        this.recipients = recipients;
        this.failurePolicy = failurePolicy;
    }

    /**
     * Returns the immutable recipient policy.
     *
     * @return declared plugin recipients
     */
    public final @NonNull Recipients recipients() {
        return recipients;
    }

    /**
     * Returns the immutable nonfatal failure policy.
     *
     * @return declared treatment of nonfatal handler failures
     */
    public final @NonNull FailurePolicy failurePolicy() {
        return failurePolicy;
    }

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
