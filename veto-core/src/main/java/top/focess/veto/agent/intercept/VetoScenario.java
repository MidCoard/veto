package top.focess.veto.agent.intercept;

/**
 * The scenario a veto pause belongs to. Determined by the {@link
 * top.focess.veto.agent.intercept.GatewayResult} the {@link
 * top.focess.veto.agent.intercept.Gateway} produced (which depends on the call's risk category and
 * what tripped). The scenario fixes the offered option set; one shared HITL mechanism, several
 * resolution vocabularies.
 *
 * <p>Per {@code screening_model.md} §8: the option set is <b>tool-declared</b> — the Gateway asks
 * the {@code ToolDefinition} what options this tool exposes for HITL resolution, and renders those.
 * {@link HitlRegistry} maps tool family + danger → scenario here.
 */
public enum VetoScenario {
    /** Scenario R — read-tool approval (file reads entering context as observations). */
    READ,
    /** Scenario W — write-tool drift (target changed since the agent last read it). */
    WRITE_DRIFT,
    /** Write tool, no drift — plain write approval. */
    WRITE,
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
