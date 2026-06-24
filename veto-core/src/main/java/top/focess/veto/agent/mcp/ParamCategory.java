package top.focess.veto.agent.mcp;

/**
 * What category of security check a tool parameter requires. {@code
 * plans/mvp-core/part5_agent/mcp_tool_foundation.md}.
 */
public enum ParamCategory {
    /** Needs sandbox boundary validation. */
    FILESYSTEM_PATH,
    /** Needs executable allowlist + injection scan. */
    SHELL_COMMAND,
    /** Needs secret-pattern scan before write. */
    CODE_CONTENT,
    /** Needs egress check. */
    URL,
    /** No special handling beyond the tool-level risk category. */
    GENERIC
}
