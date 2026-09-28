package top.focess.veto.api.plugin;

import org.jspecify.annotations.NonNull;

import top.focess.veto.api.plugin.contribution.PluginContributionsDirectory;
import top.focess.veto.api.plugin.service.PluginServices;
import top.focess.veto.api.plugin.storage.PluginStorage;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Host metadata, a live read-only lifecycle view, a failure signal, and host-granted services. Host
 * services are host-granted authority; a plugin never earns them by registering a category or
 * contribution, and receives only what the host chooses to expose.
 *
 * @param identity stable identity of the plugin receiving this context
 * @param failureReporter host callback for an asynchronous fatal plugin failure
 * @param stateReader live lifecycle-state reader owned by the host
 * @param hostServices immutable exact-class map of host-granted Java capabilities
 */
public record PluginContext(
        @NonNull PluginIdentity identity,
        @NonNull Runnable failureReporter,
        @NonNull Supplier<@NonNull PluginState> stateReader,
        @NonNull Map<@NonNull Class<?>, @NonNull Object> hostServices) {
    /** Defensively copies the host capability map. */
    public PluginContext {
        hostServices = Map.copyOf(hostServices);
    }

    /**
     * Reads the host's current state, not a cached copy. This is an observation, not permission to
     * start an operation; invocation admission remains the host's responsibility.
     *
     * @return the current lifecycle state
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
     *
     * @param <T> capability type
     * @param type exact capability class to locate
     * @return the granted capability, or an empty value when it is unavailable
     */
    public <T> @NonNull Optional<T> service(@NonNull Class<T> type) {
        return Optional.ofNullable(hostServices.get(type)).map(type::cast);
    }

    /**
     * Returns the host-mediated effects service required by this plugin, or fails initialization
     * when the host did not grant it. Plugins that can operate without it should use {@link
     * #service(Class)} instead. Retaining this object is not retained authorization.
     *
     * @return the granted plugin host
     * @throws IllegalStateException when the host is unavailable
     */
    public @NonNull PluginHost host() {
        return service(PluginHost.class)
                .orElseThrow(() -> new IllegalStateException("Plugin host unavailable"));
    }

    /**
     * Returns the named JSON protocol directory for plugin-to-plugin communication. During
     * initialization the directory is not yet populated; discover providers in {@code start()} or
     * later. If the host does not grant a directory, this returns an empty one.
     *
     * @return the named service directory
     */
    public @NonNull PluginServices services() {
        return service(PluginServices.class).orElse(PluginServices.EMPTY);
    }

    /**
     * Returns the live JSON directory of plugin-defined contribution groups.
     *
     * @return the directory, or an empty directory when the host has not granted it
     */
    public @NonNull PluginContributionsDirectory contributions() {
        return service(PluginContributionsDirectory.class)
                .orElse(PluginContributionsDirectory.EMPTY);
    }

    /**
     * Returns host-scoped persistence for this plugin, or fails when storage was not granted.
     * Plugins that can operate without persistence should use {@link #service(Class)} directly.
     *
     * @return storage bound to this plugin's identity
     */
    public @NonNull PluginStorage storage() {
        return service(PluginStorage.class)
                .orElseThrow(() -> new IllegalStateException("Plugin storage unavailable"));
    }

    /** Signals an asynchronous fatal plugin failure to the lifecycle owner. */
    public void reportFailure() {
        failureReporter.run();
    }
}
