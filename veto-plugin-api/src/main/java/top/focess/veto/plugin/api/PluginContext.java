package top.focess.veto.plugin.api;

import org.jspecify.annotations.NonNull;

import java.util.function.Supplier;

/** Host metadata, a live read-only lifecycle view, and a failure signal. */
public record PluginContext(
        @NonNull PluginIdentity identity,
        @NonNull Runnable failureReporter,
        @NonNull Supplier<@NonNull PluginState> stateReader) {
    /** Unmanaged contexts have no lifecycle owner; state() requires host binding. */
    public PluginContext(@NonNull PluginIdentity identity) {
        this(identity, () -> {});
    }

    public PluginContext(@NonNull PluginIdentity identity, @NonNull Runnable failureReporter) {
        this(
                identity,
                failureReporter,
                () -> {
                    throw new IllegalStateException(
                            "Plugin context is not bound to a lifecycle owner");
                });
    }

    /**
     * Reads the host's current state, not a cached copy. This is an observation, not permission to
     * start an operation; invocation admission remains the host's responsibility.
     */
    public @NonNull PluginState state() {
        return stateReader.get();
    }

    public void reportFailure() {
        failureReporter.run();
    }
}
