package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.Cancellation;

/**
 * Fired before the selected model is invoked. A handler may cancel the producer's model call;
 * {@link #prevent()} controls propagation only. The {@link ModelCall} itself is read-only.
 */
public final class BeforeModelEvent extends WorkflowEvent implements Cancellable {
    private final @NonNull ModelCall call;
    private boolean cancelled;

    /**
     * Creates the model-observation event.
     *
     * @param scope authenticated owner, session and agent identity
     * @param cancellation cooperative cancellation signal
     * @param call selected model endpoint
     */
    public BeforeModelEvent(
            Scope.@NonNull AgentScope scope,
            @NonNull Cancellation cancellation,
            @NonNull ModelCall call) {
        super(scope, cancellation);
        this.call = call;
    }

    /**
     * Returns the selected model endpoint.
     *
     * @return selected model call
     */
    public @NonNull ModelCall call() {
        return call;
    }

    /**
     * Returns whether the producer should cancel the model call after delivery.
     *
     * @return current reversible action cancellation
     */
    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Sets reversible action cancellation without changing propagation or the host stop signal.
     *
     * @param cancelled whether the producer should cancel the model call
     */
    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
