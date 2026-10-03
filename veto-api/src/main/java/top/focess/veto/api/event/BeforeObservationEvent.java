package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;

/**
 * Fired for an executed tool result after the after-tool transformation and before final ingress
 * defense and history publication, including unsuccessful results. Handlers transform {@link
 * #text()} in place; the submitter reads the final value after dispatch. Successful native
 * workspace reads also cross {@link BeforeTextCommitEvent.Phase#FILE_OBSERVATION} after this
 * transformation.
 */
public final class BeforeObservationEvent extends WorkflowEvent {
    private @NonNull String text;

    /**
     * Creates the observation event.
     *
     * @param scope authenticated owner, session and agent identity
     * @param text protected observation text
     */
    public BeforeObservationEvent(Scope.@NonNull AgentScope scope, @NonNull String text) {
        super(scope);
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
