package top.focess.veto.contract;

/**
 * Structured response types that the terminal maps to visual rendering.
 */
public enum ResponseType {
    /**
     * Styled text message.
     */
    MESSAGE,
    /**
     * Syntax-highlighted code block; meta may contain "language".
     */
    CODE,
    /**
     * Aligned columns; meta expected keys: "headers", "rows".
     */
    TABLE,
    /**
     * Error message, rendered with red prefix.
     */
    ERROR,
    /**
     * Progress bar; meta may contain "current", "total".
     */
    PROGRESS,
    /**
     * Bullet-pointed items; content is newline-separated.
     */
    LIST,
    /**
     * Dimmed hint / autocomplete suggestion.
     */
    SUGGESTION,
    /**
     * Backend requests additional user input (interactive prompt).
     */
    PROMPT
}
