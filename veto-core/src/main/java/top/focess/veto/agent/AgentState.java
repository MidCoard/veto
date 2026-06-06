package top.focess.veto.agent;

/** State machine for an Agent's lifecycle (ARCHITECTURE.md Part 1.4). */
public enum AgentState {
    IDLE,
    RUNNING,
    WAITING,
    INTERCEPTED,
    HALTED
}
