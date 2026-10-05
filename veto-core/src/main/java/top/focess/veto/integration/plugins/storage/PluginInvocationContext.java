package top.focess.veto.integration.plugins.storage;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.agent.tool.ToolCallContextHolder;

/**
 * Host-issued context for authenticated callbacks which do not run as model tools. Instances are
 * confined to the creating thread and must be closed there in reverse creation order. Context is
 * not inherited by child threads.
 */
public final class PluginInvocationContext implements AutoCloseable {
    private static final @NonNull ThreadLocal<@Nullable PluginInvocationContext> CURRENT =
            new ThreadLocal<>();
    private final PluginInvocationContext previous;
    final @NonNull UUID userId;
    final @NonNull String session;

    /** Installs this userId/session context as current on this thread until {@link #close()}. */
    public PluginInvocationContext(@NonNull UUID userId, @NonNull String session) {
        this.userId = userId;
        this.session = session;
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    static PluginInvocationContext current() {
        return CURRENT.get();
    }

    /**
     * Returns the ambient callback session, falling back to the current model-tool invocation.
     * Returns null outside a session invocation; this identity lookup does not grant authority.
     */
    public static String currentSession() {
        var callback = CURRENT.get();
        if (callback != null) return callback.session;
        var call = ToolCallContextHolder.get();
        if (call == null) return null;
        var session = call.sessionId();
        return session == null ? null : session.toString();
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
