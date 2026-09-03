package top.focess.veto.agent.mcp;

/** What category of security check a tool parameter requires. */
public enum ParamCategory {
    /** Needs sandbox boundary validation. */
    FILESYSTEM_PATH,
    /** Needs executable allowlist + injection scan. */
    SHELL_COMMAND,
    /** Needs secret-pattern scan before write. */
    CODE_CONTENT,
    /** Needs egress check. */
    URL,
    /** Text queued to the standard input of an already-authorized background task. */
    PROCESS_INPUT,
    /** No special handling beyond the tool-level risk category. */
    GENERIC
}
