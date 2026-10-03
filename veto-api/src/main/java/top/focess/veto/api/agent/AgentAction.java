package top.focess.veto.api.agent;

import org.jspecify.annotations.NonNull;

/**
 * A unit of work enqueued into an agent runner's action queue. The runner's virtual thread blocks
 * on {@code actionQueue.take} while {@link AgentState#IDLE}, wakes on an action, and processes it.
 *
 * <p>Sealed: the only actions are a user prompt (which drives the reasoning loop) and the lifecycle
 * controls.
 */
public sealed interface AgentAction
        permits AgentAction.UserPromptAction, AgentAction.WorkAction, AgentAction.CompactAction {

    /**
     * Submit a prompt for the agent to work on. A fresh {@code UserPromptAction} starts a new
     * reasoning episode with no active program. The session retains its configured capabilities.
     * Breaker trip resumption uses a {@code UserPromptAction("continue")}.
     *
     * @param prompt user-authored prompt text
     */
    record UserPromptAction(@NonNull String prompt) implements AgentAction {}

    /** An admitted plugin continuation; processed by the same request loop as user input. */
    record WorkAction() implements AgentAction {}

    /** Perform history/context compaction. */
    record CompactAction() implements AgentAction {}
}
