package top.focess.veto.agent;

/**
 * The volatile runtime state machine for an agent's virtual thread (LLD {@code
 * hybrid_loop_design.md} §4.1, {@code agent_class_design.md} §2).
 *
 * <p>State is <b>never persisted</b> — only configuration and {@link TurnRecord} history are
 * durable. The state lives on the agent's own virtual thread and is mutated only by that thread's
 * loop.
 *
 * <ul>
 *   <li>{@code IDLE} — the virtual thread is running but blocked on {@code actionQueue.take()}.
 *   <li>{@code RUNNING} — executing the prompt reasoning loop (LLM call).
 *   <li>{@code WAITING} — yielded waiting for sandboxed tool execution to complete.
 *   <li>{@code INTERCEPTED} — paused waiting for human-in-the-loop veto resolution.
 *   <li>{@code PAUSED} — paused by the user or a deadlock; parks until {@code resume()}.
 *   <li>{@code TERMINATED} — the session has ended; the virtual thread is stopped.
 * </ul>
 */
public enum AgentState {
    IDLE,
    RUNNING,
    WAITING,
    INTERCEPTED,
    PAUSED,
    TERMINATED;

    /** Whether the agent's session is still alive (not terminated). */
    public boolean isSessionAlive() {
        return this != TERMINATED;
    }
}
