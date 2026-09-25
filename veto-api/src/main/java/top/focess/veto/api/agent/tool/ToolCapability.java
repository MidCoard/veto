package top.focess.veto.api.agent.tool;

/**
 * The effect a tool can cause. Capability is independent from definition flavour and danger: it
 * selects the execution boundary and capability-specific authorization checks. Multiple tools
 * belong to one capability when they cross the same authority boundary; capabilities are not
 * intended to be one-per-tool.
 */
public enum ToolCapability {
    /** Read access to authorized workspace resources. */
    WORKSPACE_READ,
    /** Mutation access to authorized workspace resources. */
    WORKSPACE_WRITE,
    /** Creation of a new operating-system process. */
    PROCESS_EXECUTION,
    /** Inspection or control of an existing managed process. */
    TASK_CONTROL,
    /** Outbound access to an approved network destination. */
    NETWORK_EGRESS,
    /**
     * Explicit privileged host-boundary effect; independent of whether a tool is bundled or
     * installed.
     */
    PRIVILEGED,

    /** Transfer or completion of the current agent loop. */
    LOOP_CONTROL,
    /** Creation or coordination of another agent. */
    DELEGATION,
    /** Coordination of an agent group. */
    GROUP_CONTROL,
    /** Plugin-owned state/workflow; grants no host resource access and is not a sandbox. */
    PLUGIN_LOCAL,
    /** Direct interaction that waits for the current user. */
    USER_INTERACTION,
    /** Fail-closed fallback for an agent tool that has not yet declared a specific capability. */
    AGENT_CONTROL,
    /** Remote tool whose concrete effects are not known to the host. */
    REMOTE_UNKNOWN
}
