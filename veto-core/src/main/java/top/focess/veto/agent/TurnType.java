package top.focess.veto.agent;

/**
 * The type of a {@link TurnRecord}. Drives the {@code PromptCompiler}'s role mapping and the
 * append-only audit/history view.
 */
public enum TurnType {
    /** A user prompt — the start of a fresh reasoning episode. */
    USER_PROMPT,
    /** Legacy read compatibility only. New usage is metadata on an existing record. */
    TOKEN_USAGE,
    /** A user interrupt/feedback mid-episode. */
    USER_INTERRUPT,
    /** A sourced plugin/runtime observation, never a new user request. */
    RUNTIME_EVENT,
    /** Read compatibility for existing histories; new plugins use RUNTIME_EVENT. */
    MONITOR_EVENT,
    /** Operational reasoning text. Older records may contain legacy response JSON. */
    ASSISTANT_THOUGHT,
    /** A user-facing message the agent emitted ({@code response.message}). */
    ASSISTANT_RESPONSE,
    /** Execution failure; request-bound cancellation also supplies a runtime context boundary. */
    EXECUTION_ERROR,
    /** A tool call the agent issued ({@code calls[]} entry). */
    TOOL_CALL,
    /** The (framed) observation returned for a tool call. */
    TOOL_RESPONSE,
    /** A rewind directive — 0-based suffix-drop of the compiled view (not emitted as a message). */
    REWIND,
    /** An ordered system-prompt insertion carrying the role and concrete model binding. */
    AGENT_INIT,
    /** A compaction summary seed, re-injected after a {@link #REWIND}. */
    COMPACTION_SUMMARY
}
