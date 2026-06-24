package top.focess.veto.sandbox;

/**
 * How Veto connects the discrete commands in a {@code run_command} chain. The literal {@code &&}/
 * {@code ;} never appears in model input — the model declares intent, Veto implements it.
 * Transcribed from {@code container_sandbox_isolation.md} §5 / {@code mcp_tool_foundation.md}
 * §10.6.
 */
public enum ChainMode {
    /** Abort the chain on the first non-zero exit ({@code &&}-style). Default. */
    STOP_ON_FAILURE,
    /** Run every command regardless of exit code ({@code ;}-style). */
    RUN_ALL,
    /** Veto pipes adjacent commands' stdout → stdin. */
    PIPE
}
