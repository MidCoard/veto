package top.focess.veto.integration.plugins.storage;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Host-issued context for authenticated callbacks which do not run as model tools. */
public final class PluginInvocationScope implements AutoCloseable {
    private static final @NonNull ThreadLocal<@Nullable PluginInvocationScope> CURRENT =
            new ThreadLocal<>();
    private final PluginInvocationScope previous;
    final @NonNull String owner;
    final @NonNull String session;

    /** Installs this owner/session context as current on this thread until {@link #close()}. */
    public PluginInvocationScope(@NonNull String owner, @NonNull String session) {
        this.owner = owner;
        this.session = session;
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    static PluginInvocationScope current() {
        return CURRENT.get();
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
