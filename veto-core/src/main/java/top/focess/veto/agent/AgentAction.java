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
                AgentAction.DirectUserPromptAction,
                AgentAction.MonitorAction,
                AgentAction.PauseAction,
                AgentAction.ResumeAction,
                AgentAction.TerminateAction,
                AgentAction.CompactAction {

    /**
     * Submit a prompt for the agent to work on. A fresh {@code UserPromptAction} starts a new
     * reasoning episode with no active program. The session retains its configured capabilities.
     * Breaker trip resumption uses a {@code UserPromptAction("continue")}.
     */
    record UserPromptAction(@NonNull String prompt) implements AgentAction {}

    /** A direct user request, queued without replacing a workflow's pending result. */
    record DirectUserPromptAction(@NonNull String prompt) implements AgentAction {}

    /** A wake hint; sourced observations are read from the Monitor inbox by the same Runner. */
    record MonitorAction() implements AgentAction {}

    /** Pause the agent → {@link AgentState#PAUSED}. */
    record PauseAction() implements AgentAction {}

    /** Resume a paused agent → {@link AgentState#RUNNING}. */
    record ResumeAction() implements AgentAction {}

    /** Terminate the session → {@link AgentState#TERMINATED}; the virtual thread stops. */
    record TerminateAction() implements AgentAction {}

    /** Perform history/context compaction. */
    record CompactAction() implements AgentAction {}
}
