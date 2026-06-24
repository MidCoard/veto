package top.focess.veto.agent.intercept;

/**
 * The scenario a veto pause belongs to (LLD {@code hybrid_loop_design.md} §4.3.2, {@code
 * network_hitl_protocol.md} §4). Determined by the {@link Verdict} the {@link Gateway} produced
 * (which depends on the call's risk category and what tripped). The scenario fixes the offered
 * option set; one shared HITL mechanism, several resolution vocabularies.
 */
public enum VetoScenario {
    /** Scenario R — read-tool approval (file reads entering context as observations). */
    READ,
    /** Scenario W — write-tool drift (target changed since the agent last read it). */
    WRITE_DRIFT,
    /**
     * Scenario E1 — exec/network deterministic rule trip (out-of-bounds path, blacklisted pattern).
     */
    EXEC_DETERMINISTIC,
    /** Scenario E2 — exec/network advisory semantic flag (destructive/exfiltration intent). */
    EXEC_SEMANTIC,
    /** Scenario E3 — exec/network first-time pattern (not blacklisted, no session rule yet). */
    EXEC_FIRST_TIME,
    /** Generic fallback for external MCP tools that declare no custom options. */
    GENERIC
}
