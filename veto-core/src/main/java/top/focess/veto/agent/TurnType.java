package top.focess.veto.agent;

/**
 * The type of a {@link TurnRecord}. Drives the {@code PromptCompiler}'s role mapping and the
 * append-only audit/history view.
 */
public enum TurnType {
    /** A user prompt — the start of a fresh reasoning episode. */
    USER_PROMPT,
    /** A user interrupt/feedback mid-episode. */
    USER_INTERRUPT,
    /** The agent's reasoning — the raw {@code VetoResponse} JSON for a thought-ON turn. */
    ASSISTANT_THOUGHT,
    /** A user-facing message the agent emitted ({@code response.message}). */
    ASSISTANT_RESPONSE,
    /** A tool call the agent issued ({@code calls[]} entry). */
    TOOL_CALL,
    /** The (framed) observation returned for a tool call. */
    TOOL_RESPONSE,
    /** A rewind directive — 0-based suffix-drop of the compiled view (not emitted as a message). */
    REWIND,
    /** A role-start marker — delimits a role-segment (session start, delegation transform). */
    AGENT_INIT,
    /** A compaction summary seed, re-injected after a {@link #REWIND}. */
    COMPACTION_SUMMARY,
    /**
     * A recall directive - suffix-drops the compiled view to {@code from_index} (keeping the seed
     * turns, e.g. AGENT_INIT), then re-injects a recalled brief as a user message. Like {@link
     * #REWIND}/{@link #COMPACTION_SUMMARY} it is a compiler directive, not captured to LTM.
     */
    RECALL
}
