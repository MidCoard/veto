package top.focess.veto.vault;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.NonNull;

/**
 * Thread-local context holder for the currently authenticated user in Veto. This enables multi-user
 * request threads (e.g. from REST API endpoints or specific ZMQ terminal identities) to access
 * their respective credentials in the shared vault without changing method signatures.
 */
public final class UserContext {

    private static final @NonNull ConcurrentMap<Thread, String> CURRENT_USERS =
            new ConcurrentHashMap<>();

    private UserContext() {}

    /**
     * Sets the authenticated username for the current thread.
     *
     * @param username the username to set
     */
    public static void set(@NonNull String username) {
        CURRENT_USERS.put(Thread.currentThread(), username);
    }

    /**
     * Gets the authenticated username for the current thread.
     *
     * @return the username, or null if not set
     */
    public static String get() {
        return CURRENT_USERS.get(Thread.currentThread());
    }

    /** Clears the context for the current thread. */
    public static void clear() {
        CURRENT_USERS.remove(Thread.currentThread());
    }
}
