package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.Scope;

/**
 * Fired before user input reaches the model. Handlers transform {@link #text()} in place; the
 * submitter reads the final value after dispatch.
 */
public final class BeforeInputEvent extends WorkflowEvent {
    private @NonNull String text;

    /**
     * Creates the input event.
     *
     * @param scope authenticated owner, session and agent identity
     * @param text protected input text
     */
    public BeforeInputEvent(Scope.@NonNull AgentScope scope, @NonNull String text) {
        super(scope);
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
