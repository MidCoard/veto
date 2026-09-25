package top.focess.veto.api.plugin;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.service.PluginServices;
import top.focess.veto.api.plugin.storage.PluginStorage;

/**
 * Host metadata, a live read-only lifecycle view, a failure signal, and host-granted services. Host
 * services are host-granted authority; a plugin never earns them by registering a category or
 * contribution, and receives only what the host chooses to expose.
 */
public record PluginContext(
        @NonNull PluginIdentity identity,
        @NonNull Runnable failureReporter,
        @NonNull Supplier<@NonNull PluginState> stateReader,
        @NonNull Map<@NonNull Class<?>, @NonNull Object> hostServices) {
    public PluginContext {
        hostServices = Map.copyOf(hostServices);
    }

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

    /** Unmanaged context carrying host services; state() requires host binding. */
    public PluginContext(
            @NonNull PluginIdentity identity,
            @NonNull Map<@NonNull Class<?>, @NonNull Object> hostServices) {
        this(
                identity,
                () -> {},
                () -> {
                    throw new IllegalStateException(
                            "Plugin context is not bound to a lifecycle owner");
                },
                hostServices);
    }

    public PluginContext(
            @NonNull PluginIdentity identity,
            @NonNull Runnable failureReporter,
            @NonNull Supplier<@NonNull PluginState> stateReader) {
        this(identity, failureReporter, stateReader, Map.of());
    }

    /**
     * Reads the host's current state, not a cached copy. This is an observation, not permission to
     * start an operation; invocation admission remains the host's responsibility.
     */
    public @NonNull PluginState state() {
        return stateReader.get();
    }

    /**
     * Looks up an optional host-granted Java capability by exact class identity.
     *
     * <p>This is distinct from {@link #services()}, which discovers plugin-provided named JSON
     * protocols. Registration never earns a host capability. Absence is normal, and a retained
     * instance remains subject to lifecycle and call-specific authorization checks.
     */
    public <T> @NonNull Optional<T> service(@NonNull Class<T> type) {
        return Optional.ofNullable(hostServices.get(type)).map(type::cast);
    }

    /**
     * Returns the named JSON protocol directory for plugin-to-plugin communication. During
     * initialization the directory is not yet populated; discover providers in {@code start()} or
     * later. If the host does not grant a directory, this returns an empty one.
     */
    public @NonNull PluginServices services() {
        return service(ToolDocs.nonNullClass(PluginServices.class)).orElse(PluginServices.EMPTY);
    }

    /**
     * Returns host-scoped persistence for this plugin, or fails when storage was not granted.
     * Plugins that can operate without persistence should use {@link #service(Class)} directly.
     */
    public @NonNull PluginStorage storage() {
        return service(ToolDocs.nonNullClass(PluginStorage.class))
                .orElseThrow(() -> new IllegalStateException("Plugin storage unavailable"));
    }

    /** Signals an asynchronous fatal plugin failure to the lifecycle owner. */
    public void reportFailure() {
        failureReporter.run();
    }
}
