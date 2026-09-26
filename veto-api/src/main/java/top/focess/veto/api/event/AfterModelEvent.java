package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.Cancellation;

/**
 * Fired after a model response is produced. Handlers transform the response text in place; native
 * calls and provider-owned signed state are preserved by the host and are not part of this payload.
 */
public final class AfterModelEvent extends WorkflowEvent {
    private final @NonNull ModelCall call;
    private String message;

    /**
     * Creates the model-output event.
     *
     * @param owner authenticated owner, or {@code null} when unavailable
     * @param sessionId current session identity
     * @param agentId current agent identity
     * @param cancellation cooperative cancellation signal
     * @param call selected model endpoint
     * @param message model text, or {@code null} when the response contains no text
     */
    public AfterModelEvent(
            String owner,
            @NonNull String sessionId,
            @NonNull String agentId,
            @NonNull Cancellation cancellation,
            @NonNull ModelCall call,
            String message) {
        super(owner, sessionId, agentId, cancellation);
        this.call = call;
        this.message = message;
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
     * Returns the current model text.
     *
     * @return model text, or {@code null} when the response contains no text
     */
    public String message() {
        return message;
    }

    /**
     * Replaces the model text.
     *
     * @param message transformed text, or {@code null} to clear it
     */
    public void setMessage(String message) {
        this.message = message;
    }
}
