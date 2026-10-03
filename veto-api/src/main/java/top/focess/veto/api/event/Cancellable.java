package top.focess.veto.api.event;

/**
 * Optional reversible cancellation of the producer's action after event delivery.
 *
 * <p>A later handler may clear this flag; the producer reads its final value after delivery.
 * Cancellation does not stop propagation, which is controlled by {@link Event#prevent()}; a handler
 * may individually opt out through {@link EventHandler#notCallIfCancelled()}. Cancellation cannot
 * relax a separate host security decision. The read-only cooperative host stop signal from {@link
 * Event#cancellation()} is independent of this mutable action flag.
 */
public interface Cancellable {
    /**
     * Reports whether the producer's action should be cancelled after delivery.
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

    /** Requests cancellation of the producer's action after delivery. */
    default void cancel() {
        setCancelled(true);
    }
}
