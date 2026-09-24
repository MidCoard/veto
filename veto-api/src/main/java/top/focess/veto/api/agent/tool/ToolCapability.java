package top.focess.veto.api.agent.tool;

/**
 * The effect a tool can cause. Capability is independent from definition flavour and danger: it
 * selects the execution boundary and capability-specific authorization checks. Multiple tools
 * belong to one capability when they cross the same authority boundary; capabilities are not
 * intended to be one-per-tool.
 */
public enum ToolCapability {
    WORKSPACE_READ,
    WORKSPACE_WRITE,
    PROCESS_EXECUTION,
    TASK_CONTROL,
    NETWORK_EGRESS,
    /**
     * Explicit privileged host-boundary effect; independent of whether a tool is bundled or
     * installed.
     */
    PRIVILEGED,

    LOOP_CONTROL,
    DELEGATION,
    GROUP_CONTROL,
    /** Plugin-owned state/workflow; grants no host resource access and is not a sandbox. */
    PLUGIN_LOCAL,
    USER_INTERACTION,
    /** Fail-closed fallback for an agent tool that has not yet declared a specific capability. */
    AGENT_CONTROL,
    REMOTE_UNKNOWN
}
