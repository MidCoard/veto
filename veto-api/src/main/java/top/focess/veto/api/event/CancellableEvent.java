package top.focess.veto.api.event;

/**
 * Opt-in base for non-security notification events that carry reversible cancellation in addition
 * to the always-available irreversible {@link Event#prevent()}.
 *
 * <p>Every event is preventable, but cancellation is a capability an event must choose. Extending
 * this class adopts {@link Cancellable}, so a later handler may clear the flag; that reversibility
 * is why cancellation must never guard a security decision. The workflow decision chain ({@link
 * WorkflowEvent} and its subclasses) is deliberately NOT cancellable: it halts only through the
 * irreversible {@link Event#prevent()} veto and aborts at run level through {@link
 * WorkflowEvent#cancellation()}, never through a reversible per-event cancel. Use this base only
 * for best-effort notifications whose suppression a later handler may safely undo.
 */
public abstract class CancellableEvent extends Event implements Cancellable {
    private boolean cancelled;

    /** Creates the optional cancellation state for a non-security notification event. */
    protected CancellableEvent() {}

    /**
     * Reports whether the event has been cancelled.
     *
     * @return current cancellation state
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Sets the cancellation state; passing {@code false} reverses an earlier cancellation.
     *
     * @param cancelled new cancellation state
     */
    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
