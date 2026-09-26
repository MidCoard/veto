package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.Cancellation;

/**
 * Fired before an ordinary observation is published. Handlers transform {@link #text()} in place;
 * the submitter reads the final value after dispatch.
 */
public final class BeforeObservationEvent extends WorkflowEvent {
    private @NonNull String text;

    /**
     * Creates the observation event.
     *
     * @param owner authenticated owner, or {@code null} when unavailable
     * @param sessionId current session identity
     * @param agentId current agent identity
     * @param cancellation cooperative cancellation signal
     * @param text protected observation text
     */
    public BeforeObservationEvent(
            String owner,
            @NonNull String sessionId,
            @NonNull String agentId,
            @NonNull Cancellation cancellation,
            @NonNull String text) {
        super(owner, sessionId, agentId, cancellation);
        this.text = text;
    }

    /**
     * Returns the current observation text.
     *
     * @return current text
     */
    public @NonNull String text() {
        return text;
    }

    /**
     * Replaces the observation text.
     *
     * @param text transformed text
     */
    public void setText(@NonNull String text) {
        this.text = text;
    }
}
