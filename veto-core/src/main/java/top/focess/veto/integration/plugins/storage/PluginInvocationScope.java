package top.focess.veto.integration.plugins.storage;

import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** Host-issued context for authenticated callbacks which do not run as model tools. */
@NullMarked
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public final class PluginInvocationScope implements AutoCloseable {
    private static final ThreadLocal<@Nullable PluginInvocationScope> CURRENT = new ThreadLocal<>();
    private final @Nullable PluginInvocationScope previous;
    final String owner;
    final String session;

    public PluginInvocationScope(String owner, String session) {
        this.owner = owner;
        this.session = session;
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    static @Nullable PluginInvocationScope current() {
        return CURRENT.get();
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
