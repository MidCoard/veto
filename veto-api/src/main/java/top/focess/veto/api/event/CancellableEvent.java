package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;

/**
 * Opt-in base for events with reversible producer-action cancellation.
 *
 * <p>A later handler may clear the action flag; the producer reads the final value after delivery.
 * It neither stops propagation nor relaxes a separate host security decision. Propagation uses
 * {@link Event#prevent()}, while the cooperative read-only host stop signal uses {@link
 * Event#cancellation()}. Its flags have the same single-dispatch confinement as {@link Event};
 * reversible cancellation does not make an event safe for cross-thread mutation.
 */
public abstract class CancellableEvent extends Event implements Cancellable {
    private boolean cancelled;

    /**
     * Creates reversible cancellation state with explicit delivery policies.
     *
     * @param recipients plugin recipients to resolve
     * @param failurePolicy treatment of nonfatal handler failures
     */
    protected CancellableEvent(
            @NonNull Recipients recipients, @NonNull FailurePolicy failurePolicy) {
        super(recipients, failurePolicy);
    }

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
