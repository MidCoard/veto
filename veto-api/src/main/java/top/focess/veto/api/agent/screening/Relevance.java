package top.focess.veto.api.agent.screening;

/** Semantic relevance classification for a proposed tool call. */
public enum Relevance {
    /** Strong semantic relevance to the current task. */
    HIGH,
    /** Moderate semantic relevance to the current task. */
    MEDIUM,
    /** Weak semantic relevance to the current task. */
    LOW
}
