package top.focess.veto.contract;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Standard metadata keys used in {@link IpcFrame.Done#meta}.
 *
 * <p>Use these constants instead of raw strings to prevent typos and make metadata usage
 * discoverable across the codebase. The typed {@code...Value} accessors perform the safe casts so
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

    // ── typed, null-safe accessors ──────────────────────────────────────────

    /**
     * Reads the username from a meta map.
     *
     * @param meta the metadata map
     * @return the username, or {@code null} if absent or not a string
     */
    public static @Nullable String username(@NonNull Map<String, Object> meta) {
        Object v = meta.get(USERNAME);
        return v instanceof String s ? s : null;
    }

    /**
     * Reads the session id from a meta map.
     *
     * @param meta the metadata map
     * @return the session id, or {@code null} if absent or not a string
     */
    public static @Nullable String session(@NonNull Map<String, Object> meta) {
        Object v = meta.get(SESSION);
        return v instanceof String s ? s : null;
    }

    /**
     * Reads the turn number from a meta map.
     *
     * @param meta the metadata map
     * @param def the value to return if the key is absent or not numeric
     * @return the turn number, or {@code def} if absent or not numeric
     */
    public static int turnNumber(@NonNull Map<String, Object> meta, int def) {
        Object v = meta.get(TURN_NUMBER);
        return v instanceof Number n ? n.intValue() : def;
    }

    /** True if the meta marks the request cancelled. */
    public static boolean cancelled(@NonNull Map<String, Object> meta) {
        return Boolean.TRUE.equals(meta.get(CANCELLED));
    }

    /** True if the meta instructs the terminal to clear cached session metadata. */
    public static boolean clearSession(@NonNull Map<String, Object> meta) {
        return Boolean.TRUE.equals(meta.get(CLEAR_SESSION));
    }
}
