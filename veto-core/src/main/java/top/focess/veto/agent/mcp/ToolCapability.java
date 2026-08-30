package top.focess.veto.agent.mcp;

/**
 * The effect a tool can cause. Capability is independent from definition flavour and risk: it
 * selects the execution boundary and the capability-specific authorization checks.
 */
public enum ToolCapability {
    WORKSPACE_READ,
    WORKSPACE_WRITE,
    PROCESS_EXECUTION,
    TASK_CONTROL,
    NETWORK_EGRESS,
    SKILL_READ,
    MEMORY_READ,
    MEMORY_WRITE,
    AGENT_CONTROL,
    REMOTE_UNKNOWN
}
