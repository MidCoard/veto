package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.Cancellation;
import top.focess.veto.api.plugin.contract.JsonValue;

/**
 * Fired after a tool has executed. Handlers transform the observation body in place; the actual
 * execution status and result format are preserved by the host and cannot be altered here.
 */
public final class AfterToolEvent extends WorkflowEvent {
    /**
     * Actual tool output observed by handlers.
     *
     * @param format result format fixed by the host
     * @param success actual execution success flag fixed by the host
     */
    public record Output(@NonNull ToolResultFormat format, boolean success) {}

    private final BeforeToolEvent.@NonNull Invocation invocation;
    private final @NonNull Output output;
    private @NonNull String content;

    /**
     * Creates the tool-observation event.
     *
     * @param scope authenticated owner, session and agent identity
     * @param cancellation cooperative cancellation signal
     * @param invocation immutable tool invocation that produced the output
     * @param output host-fixed format and success flag
     * @param content observation text handlers may transform
     */
    public AfterToolEvent(
            Scope.@NonNull AgentScope scope,
            @NonNull Cancellation cancellation,
            BeforeToolEvent.@NonNull Invocation invocation,
            @NonNull Output output,
            @NonNull String content) {
        super(scope, cancellation);
        this.invocation = invocation;
        this.output = output;
        this.content = content;
    }

    /**
     * Returns the invocation that produced the output.
     *
     * @return immutable tool invocation
     */
    public BeforeToolEvent.@NonNull Invocation invocation() {
        return invocation;
    }

    /**
     * Returns the host-fixed output format and success flag.
     *
     * @return immutable output metadata
     */
    public @NonNull Output output() {
        return output;
    }

    /**
     * Returns the current observation body.
     *
     * @return observation text
     */
    public @NonNull String content() {
        return content;
    }

    /**
     * Replaces the observation body.
     *
     * @param content transformed observation text
     */
    public void setContent(@NonNull String content) {
        this.content = content;
    }

    /**
     * Convenience accessor for the invocation arguments.
     *
     * @return validated immutable arguments
     */
    public JsonValue.@NonNull ObjectValue arguments() {
        return invocation.arguments();
    }
}
