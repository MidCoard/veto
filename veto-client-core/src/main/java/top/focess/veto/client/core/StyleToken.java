package top.focess.veto.client.core;

/**
 * Semantic styling tokens — the shared "palette" both client presentations render against.
 *
 * <p>Instead of each client hard-coding colors per call site (and diverging — Progress was
 * bright-gray in the TUI but dim in the REPL; Error was bright-red vs red), both clients style the
 * same semantic content with the same token, and each {@link Theme} maps the token to its native
 * styled form. The set is intentionally small and closed: adding a token is a deliberate act that
 * touches every theme, not a speculative theming system.
 */
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
    /** Default body text — no styling (Delta/Done content, input text). */
    PLAIN
}
