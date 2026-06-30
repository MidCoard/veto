package top.focess.veto.agent;

import org.jspecify.annotations.NonNull;

/**
 * A unit of work enqueued into an {@code AgentRunner}'s action queue ( ). The runner's virtual
 * thread blocks on {@code actionQueue.take} while {@link AgentState#IDLE}, wakes on an action, and
 * processes it.
 *
 * <p>Sealed: the only actions are a user prompt (which drives the reasoning loop) and the lifecycle
 * controls.
 */
public sealed interface AgentAction
        permits AgentAction.UserPromptAction,
                AgentAction.PauseAction,
                AgentAction.ResumeAction,
                AgentAction.TerminateAction,
                AgentAction.CompactAction {

    /**
     * Submit a prompt for the agent to work on. A fresh {@code UserPromptAction} starts a new
     * reasoning episode: prior-turn {@code features}/{@code guided} are reset (autonomous) and the
     * effective thought flag is forced ON for the first model call ( ). Breaker trip resumption is
     * just a {@code UserPromptAction("continue")}.
     */
    record UserPromptAction(@NonNull String prompt) implements AgentAction {
        public UserPromptAction {
            if (prompt == null) {
                prompt = "";
            }
        }
    }

    /** Pause the agent → {@link AgentState#PAUSED}. */
    record PauseAction() implements AgentAction {}

    /** Resume a paused agent → {@link AgentState#RUNNING}. */
    record ResumeAction() implements AgentAction {}

    /** Terminate the session → {@link AgentState#TERMINATED}; the virtual thread stops. */
    record TerminateAction() implements AgentAction {}

    /** Perform history/context compaction. */
    record CompactAction() implements AgentAction {}
}
