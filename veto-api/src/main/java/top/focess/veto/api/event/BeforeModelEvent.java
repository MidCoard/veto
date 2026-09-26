package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.Cancellation;

/**
 * Fired before the selected model is invoked. This is an observation point: a handler may {@link
 * #prevent()} the call but the {@link ModelCall} itself is read-only.
 */
public final class BeforeModelEvent extends WorkflowEvent {
    private final @NonNull ModelCall call;

    /**
     * Creates the model-observation event.
     *
     * @param owner authenticated owner, or {@code null} when unavailable
     * @param sessionId current session identity
     * @param agentId current agent identity
     * @param cancellation cooperative cancellation signal
     * @param call selected model endpoint
     */
    public BeforeModelEvent(
            String owner,
            @NonNull String sessionId,
            @NonNull String agentId,
            @NonNull Cancellation cancellation,
            @NonNull ModelCall call) {
        super(owner, sessionId, agentId, cancellation);
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
}
