package top.focess.veto.terminal.client;

/** Semantic styling tokens used by the terminal renderer. */
public enum StyleToken {
    /** Primary highlight — cyan (header title, connection-OK, logged-in prompt marker). */
    ACCENT,
    /** Secondary text — dim/bright-black (borders, footer, Progress, "thinking…", scroll hints). */
    MUTED,
    /** Failures — red (Error content, logged-out prompt marker, Disconnected). */
    ERROR,
    /** Positive outcomes — green (logged-in username, Connected). */
    SUCCESS,
    /** Cautious / transitional — yellow (turn count, Connecting). */
    WARNING,
    /** The server-prompted input marker specifically — yellow + bold. */
    PROMPT,
    /** Box / panel borders — dim/bright-black. */
    BORDER,
    /** Default body text — no styling (message content, input text). */
    PLAIN
}
