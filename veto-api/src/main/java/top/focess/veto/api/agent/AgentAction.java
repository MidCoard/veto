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
        permits AgentAction.UserPromptAction,
                AgentAction.DirectUserPromptAction,
                AgentAction.WorkAvailableAction,
                AgentAction.WorkAction,
                AgentAction.TerminateAction,
                AgentAction.CompactAction,
                AgentAction.ConfigurationAction {

    /**
     * Submit a prompt for the agent to work on. A fresh {@code UserPromptAction} starts a new
     * reasoning episode with no active program. The session retains its configured capabilities.
     * Breaker trip resumption uses a {@code UserPromptAction("continue")}.
     *
     * @param prompt user-authored prompt text
     */
    record UserPromptAction(@NonNull String prompt) implements AgentAction {}

    /**
     * A direct user request, queued without replacing a workflow's pending result.
     *
     * @param prompt user-authored prompt text
     */
    record DirectUserPromptAction(@NonNull String prompt) implements AgentAction {}

    /** A wake hint; sourced observations are read from plugin work sources by the same Runner. */
    record WorkAvailableAction() implements AgentAction {}

    /** An admitted plugin continuation; processed by the same request loop as user input. */
    record WorkAction() implements AgentAction {}

    /** Terminate the session → {@link AgentState#TERMINATED}; the virtual thread stops. */
    record TerminateAction() implements AgentAction {}

    /** Apply pending host configuration on the agent execution thread. */
    record ConfigurationAction() implements AgentAction {}

    /** Perform history/context compaction. */
    record CompactAction() implements AgentAction {}
}
