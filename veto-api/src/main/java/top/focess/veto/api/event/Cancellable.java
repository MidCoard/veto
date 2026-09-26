package top.focess.veto.api.event;

/**
 * Optional reversible cancellation for non-security notification events.
 *
 * <p>A later handler may clear this flag, so it must never guard a security decision. Security
 * gates use {@link Event#prevent()}, which is irreversible and cannot be overturned downstream.
 */
public interface Cancellable {
    /**
     * Reports whether the event has been cancelled.
     *
     * @return current cancellation state
     */
    boolean isCancelled();

    /**
     * Sets the cancellation state; passing {@code false} reverses an earlier cancellation.
     *
     * @param cancelled new cancellation state
     */
    void setCancelled(boolean cancelled);

    /** Cancels the event. */
    default void cancel() {
        setCancelled(true);
    }
}
