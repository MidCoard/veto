package top.focess.veto.api.event;

/**
 * Opt-in base for non-security notification events that carry reversible cancellation in addition
 * to the always-available irreversible {@link Event#prevent()}.
 *
 * <p>Every event is preventable, but cancellation is a capability an event must choose. Extending
 * this class adopts {@link Cancellable}, so a later handler may clear the flag; that reversibility
 * is why cancellation must never guard a security decision. The workflow decision chain ({@link
 * WorkflowEvent} and its subclasses) deliberately does not implement this reversible flag: {@link
 * Event#prevent()} skips later handlers by default, and the concrete producer determines whether it
 * vetoes a host action. Run cancellation uses {@link WorkflowEvent#cancellation()}, never a
 * reversible per-event flag. Use this base only for best-effort notifications whose suppression a
 * later handler may safely undo. Its flags have the same single-dispatch confinement as {@link
 * Event}; reversible cancellation does not make an event safe for cross-thread mutation.
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
