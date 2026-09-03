package top.focess.veto.agent.mcp;

/**
 * The effect a tool can cause. Capability is independent from definition flavour and risk: it
 * selects the execution boundary and the capability-specific authorization checks. Multiple tools
 * belong to one capability when they cross the same authority boundary; capabilities are not
 * intended to be one-per-tool.
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
    LOOP_CONTROL,
    DELEGATION,
    GROUP_CONTROL,
    USER_INTERACTION,
    /** Fail-closed fallback for an agent tool that has not yet declared a specific capability. */
    AGENT_CONTROL,
    REMOTE_UNKNOWN
}
