package top.focess.veto.contract;

import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Standard metadata keys used in {@link IpcFrame.Done#meta()} and {@link IpcFrame.Prompt#meta()}.
 *
 * <p>Use these constants instead of raw strings to prevent typos and make metadata usage
 * discoverable across the codebase. The typed {@code ...Value} accessors perform the safe casts so
 * callers never hand-cast the loosely-typed map (which risked {@link ClassCastException} when a
 * peer sent an unexpected value type).
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

    // ── typed, null-safe accessors ──────────────────────────────────────────

    /**
     * Reads the username from a meta map.
     *
     * @param meta the metadata map
     * @return the username, or {@code null} if absent or not a string
     */
    public static @Nullable String username(@NotNull Map<String, Object> meta) {
        Object v = meta.get(USERNAME);
        return v instanceof String s ? s : null;
    }

    /**
     * Reads the turn number from a meta map.
     *
     * @param meta the metadata map
     * @param def the value to return if the key is absent or not numeric
     * @return the turn number, or {@code def} if absent or not numeric
     */
    public static int turnNumber(@NotNull Map<String, Object> meta, int def) {
        Object v = meta.get(TURN_NUMBER);
        return v instanceof Number n ? n.intValue() : def;
    }

    /** True if the meta marks the request cancelled. */
    public static boolean cancelled(@NotNull Map<String, Object> meta) {
        return Boolean.TRUE.equals(meta.get(CANCELLED));
    }

    /** True if the meta instructs the terminal to clear cached session metadata. */
    public static boolean clearSession(@NotNull Map<String, Object> meta) {
        return Boolean.TRUE.equals(meta.get(CLEAR_SESSION));
    }

    /** True if the prompt meta requests masked input. */
    public static boolean mask(@NotNull Map<String, Object> meta) {
        return Boolean.TRUE.equals(meta.get(MASK));
    }

    /** Reads the prompt display text from a meta map, or {@code null} if absent. */
    public static @Nullable String promptText(@NotNull Map<String, Object> meta) {
        Object v = meta.get(PROMPT);
        return v instanceof String s ? s : null;
    }
}
