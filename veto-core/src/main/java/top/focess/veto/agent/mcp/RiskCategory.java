package top.focess.veto.agent.mcp;

/** What kind of danger a tool represents. Read by the Gateway to decide screening level. */
public enum RiskCategory {
    /** File reads, directory listing — bypass semantic screening. */
    READ_ONLY,
    /** File writes, replacements — needs semantic screening. */
    FILE_WRITE,
    /** Command execution — full screening + allowlist + parser routing. */
    SHELL_EXEC,
    /** Egress — needs semantic screening. */
    NETWORK,
    /**
     * Engine-provided control/meta tool (create_group, load_skill). Carries no host-path
     * parameters, so the Gateway does not path/semantic-screen it.
     */
    AGENT
}
