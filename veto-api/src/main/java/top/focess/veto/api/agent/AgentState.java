package top.focess.veto.api.agent;

/**
 * The volatile runtime state machine for an agent's virtual thread.
 *
 * <p>State is <b>never persisted</b> — only configuration and turn history are durable. The state
 * lives on the agent's own virtual thread and is mutated only by that thread's loop.
 *
 * <ul>
 *   <li>{@code IDLE} — the virtual thread is running but blocked on {@code actionQueue.take}.
 *   <li>{@code RUNNING} — executing the prompt reasoning loop (LLM call).
 *   <li>{@code WAITING} — yielded waiting for sandboxed tool execution to complete.
 *   <li>{@code INTERCEPTED} — paused waiting for human-in-the-loop veto resolution.
 *   <li>{@code PAUSED} — paused by the user or a deadlock; parks until {@code resume}.
 *   <li>{@code TERMINATED} — the session has ended; the virtual thread is stopped.
 * </ul>
 */
public enum AgentState {
    /** Alive and waiting for the next action. */
    IDLE,
    /** Running model or workflow logic. */
    RUNNING,
    /** Waiting for a tool operation to complete. */
    WAITING,
    /** Waiting for a human decision on a screened operation. */
    INTERCEPTED,
    /** Explicitly paused until resumed. */
    PAUSED,
    /** Permanently stopped. */
    TERMINATED;

    /**
     * Reports whether the agent session can still process actions.
     *
     * @return {@code true} unless this state is {@link #TERMINATED}
     */
    public boolean isSessionAlive() {
        return this != TERMINATED;
    }
}
