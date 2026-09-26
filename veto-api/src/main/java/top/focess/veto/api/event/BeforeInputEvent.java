package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.Cancellation;

/**
 * Fired before user input reaches the model. Handlers transform {@link #text()} in place; the
 * submitter reads the final value after dispatch.
 */
public final class BeforeInputEvent extends WorkflowEvent {
    private @NonNull String text;

    /**
     * Creates the input event.
     *
     * @param owner authenticated owner, or {@code null} when unavailable
     * @param sessionId current session identity
     * @param agentId current agent identity
     * @param cancellation cooperative cancellation signal
     * @param text protected input text
     */
    public BeforeInputEvent(
            String owner,
            @NonNull String sessionId,
            @NonNull String agentId,
            @NonNull Cancellation cancellation,
            @NonNull String text) {
        super(owner, sessionId, agentId, cancellation);
        this.text = text;
    }

    /**
     * Returns the current input text.
     *
     * @return current text
     */
    public @NonNull String text() {
        return text;
    }

    /**
     * Replaces the input text.
     *
     * @param text transformed text
     */
    public void setText(@NonNull String text) {
        this.text = text;
    }
}
