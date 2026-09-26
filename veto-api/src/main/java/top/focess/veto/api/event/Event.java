package top.focess.veto.api.event;

/**
 * Base type for every host-dispatched plugin event.
 *
 * <p>A concrete event carries a mutable payload that handlers transform in place; the submitter
 * reads the result once dispatch returns. The {@code prevent} flag irreversibly skips later
 * handlers by default, including supertype handlers. A handler that opts in to seeing prevented
 * events cannot clear that flag. The host uses it for monotonic security decisions, such as a tool
 * veto that no later plugin may overturn. Every event is preventable through this base;
 * cancellation is not. An event that also needs reversible cancellation extends {@link
 * CancellableEvent}, and cancellation never guards a security gate.
 */
public abstract class Event {
    private boolean prevent;

    /** Creates the base state for a concrete host-dispatched event. */
    protected Event() {}

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
