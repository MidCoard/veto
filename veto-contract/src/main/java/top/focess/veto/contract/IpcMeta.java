package top.focess.veto.contract;

/**
 * Standard metadata keys used in {@link IpcFrame.Done#meta()} and {@link IpcFrame.Prompt#meta()}.
 *
 * <p>Use these constants instead of raw strings to prevent typos and make metadata usage
 * discoverable across the codebase.
 */
public final class IpcMeta {

    /** Private constructor to prevent instantiation of utility class. */
    private IpcMeta() {}

    // ── Done meta ───────────────────────────────────────────────────────────

    /** {@code boolean} — indicates whether the current request was cancelled by the user. */
    public static final String CANCELLED = "cancelled";

    /**
     * {@code boolean} — instructs the terminal to clear cached session metadata (such as username
     * and turn count).
     */
    public static final String CLEAR_SESSION = "clearSession";

    /** {@code String} — the username associated with the current session. */
    public static final String USERNAME = "username";

    /** {@code String} — the terminal / session identifier. */
    public static final String SESSION = "session";

    /** {@code int} — the current agent turn number in the session. */
    public static final String TURN_NUMBER = "turnNumber";

    // ── Prompt meta ─────────────────────────────────────────────────────────

    /**
     * {@code boolean} — instructs the terminal to mask input characters (e.g. for password fields).
     */
    public static final String MASK = "mask";

    /** {@code String} — the prompt text to display above the input field. */
    public static final String PROMPT = "prompt";
}
