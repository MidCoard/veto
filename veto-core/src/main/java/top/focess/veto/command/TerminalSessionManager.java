package top.focess.veto.command;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Maps terminal IDs (extracted from IPC filenames) to usernames. Session tokens no longer travel in
 * frame payloads — the backend identifies the terminal by the filename of its {@code .ipc} file.
 */
public class TerminalSessionManager {

    private final Map<String, String> sessions = new ConcurrentHashMap<>();

    /** Register a terminal as logged in. */
    public void create(@NotNull String terminalId, @NotNull String username) {
        sessions.put(terminalId, username);
    }

    /** Resolve the username for a terminal, or {@code null} if not logged in. */
    @Nullable
    public String resolve(@Nullable String terminalId) {
        return terminalId != null ? sessions.get(terminalId) : null;
    }

    /** Invalidate a terminal's session. */
    public void invalidate(@NotNull String terminalId) {
        sessions.remove(terminalId);
    }

    /** Invalidate all sessions for a given username. */
    public void invalidateAll(@Nullable String username) {
        if (username != null) {
            sessions.values().removeIf(username::equals);
        }
    }

    /** Return the number of active sessions. */
    public int count() {
        return sessions.size();
    }
}
