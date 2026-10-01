package top.focess.veto.api.plugin;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contribution.Contribution;
import top.focess.veto.api.plugin.contribution.ContributionPoint;
import top.focess.veto.api.plugin.contribution.PluginContributionsDirectory;
import top.focess.veto.api.plugin.service.PluginServices;
import top.focess.veto.api.plugin.storage.PluginStorage;

/**
 * Host metadata, a live read-only lifecycle view, a failure signal, and host-granted services. Host
 * services are host-granted authority; a plugin never earns them by registering a category or
 * contribution, and receives only what the host chooses to expose.
 *
 * <p>Concurrency depends on the bound host contracts. Identity and the outer service map are
 * immutable; the handler map is a live read-only view, not a synchronized snapshot. A host that
 * changes handlers concurrently must supply a concurrency-safe map and callbacks. Registration,
 * state reads and failure reporting call the host inline on the caller's thread. This context
 * neither serializes contributed aspect calls nor makes granted services thread-safe.
 */
public final class PluginContext {
    private final @NonNull PluginIdentity identity;
    private final @NonNull Runnable failureReporter;
    private final @NonNull Supplier<@NonNull PluginState> stateReader;
    private final @NonNull Map<@NonNull Class<?>, @NonNull Object> hostServices;
    private final @NonNull
            Map<@NonNull ContributionPoint<?>, @NonNull Consumer<@NonNull Contribution<?>>>
            handlers;

    /**
     * Creates a context with explicit handlers for the contribution points this host accepts.
     *
     * @param identity installed plugin identity
     * @param failureReporter callback for asynchronous fatal failures
     * @param stateReader current lifecycle state supplier
     * @param hostServices exact-class host grants
     * @param handlers live host-owned exact-point registration view; the host may add points at
     *     runtime, and each dispatch still belongs to this plugin
     */
    public PluginContext(
            @NonNull PluginIdentity identity,
            @NonNull Runnable failureReporter,
            @NonNull Supplier<@NonNull PluginState> stateReader,
            @NonNull Map<@NonNull Class<?>, @NonNull Object> hostServices,
            @NonNull Map<@NonNull ContributionPoint<?>, @NonNull Consumer<@NonNull Contribution<?>>>
                    handlers) {
        this.identity = identity;
        this.failureReporter = failureReporter;
        this.stateReader = stateReader;
        this.hostServices = Map.copyOf(hostServices);
        this.handlers = Collections.unmodifiableMap(handlers);
    }

    /**
     * Returns the stable identity granted to the constructing plugin.
     *
     * @return installed plugin identity
     */
    public @NonNull PluginIdentity identity() {
        return identity;
    }

    /**
     * Host integration accessor for rebinding the exact-class grants across lifecycle binding.
     * Plugin authors should use {@link #service(Class)}; registration never expands these grants.
     *
     * @return immutable host grants
     */
    public @NonNull Map<@NonNull Class<?>, @NonNull Object> hostServices() {
        return hostServices;
    }

    /**
     * Delivers one complete aspect to the handler for its exact contribution point. The handler
     * owns validation, publication, and lifecycle; this context stores no contribution objects.
     *
     * @param point the registration point and its aspect contract
     * @param localId stable plugin-local identity for this aspect
     * @param aspect the complete object to register
     * @param <T> the point's aspect type
     */
    public <T> void register(
            @NonNull ContributionPoint<T> point, @NonNull String localId, @NonNull T aspect) {
        Contribution<T> contribution = Contribution.of(point, localId, aspect);
        Consumer<Contribution<?>> handler = handlers.get(point);
        if (handler == null) throw new IllegalArgumentException("Unrecognized contribution point");
        handler.accept(contribution);
    }

    /**
     * Host integration accessor for preserving registration across lifecycle binding. Plugin
     * authors should use {@link #register}; invoking a handler still requires host validation.
     *
     * @return read-only live view of the host-owned handler map
     */
    public @NonNull Map<@NonNull ContributionPoint<?>, @NonNull Consumer<@NonNull Contribution<?>>>
            handlers() {
        return handlers;
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
     * Returns the host-mediated effects service required by this plugin, or fails construction when
     * the host did not grant it. Plugins that can operate without it should use {@link
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
     * construction the directory is not yet populated; discover providers in {@code start()} or
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

    /**
     * Reports an asynchronous fatal failure to the lifecycle owner. The owner marks this plugin
     * failed and releases its resources on its control executor. Script plugins use this when the
     * worker exits unexpectedly or violates its result contract. Ordinary tool errors must be
     * returned as tool failures instead of calling this method.
     */
    public void reportFailure() {
        failureReporter.run();
    }
}
