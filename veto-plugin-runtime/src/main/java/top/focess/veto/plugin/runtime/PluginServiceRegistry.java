package top.focess.veto.plugin.runtime;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginScope;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.service.PluginService;
import top.focess.veto.api.plugin.service.PluginServices;
import top.focess.veto.api.plugin.service.ServiceCallContext;
import top.focess.veto.api.plugin.service.ServiceException;
import top.focess.veto.api.plugin.service.ServiceHandler;
import top.focess.veto.api.plugin.storage.PluginStorage;

/**
 * Atomically bound service directory; implementation objects never escape to consumers.
 *
 * <p>Threading: bind and revoke serialize replacement of the immutable volatile directory under
 * this registry's monitor. Readers and retained handles do not take that monitor; each invocation
 * rechecks generation, visibility and lifecycle admission. Callback registrations use a concurrent
 * map. Service and callback bodies execute on caller threads outside the registry monitor, and
 * implementations must support simultaneous admitted calls. Metadata getters used by bind/revoke
 * run under the monitor and must not block or reenter management. Registration handles should be
 * released by their owning activation; invocation admission does not itself own those handles.
 * Revoked activation IDs remain as string-only tombstones for this directory's lifetime; obtaining
 * another view cannot resurrect registration. They retain no implementation objects or loaders.
 */
public final class PluginServiceRegistry {
    private record Key(@NonNull String name, int version) {}

    private record Entry(
            @NonNull PluginService service, @NonNull ManagedPlugin owner, long generation) {}

    private record Outcome(JsonValue value, ServiceException failure) {}

    private record CallbackEntry(@NonNull ServiceHandler handler, @NonNull ManagedPlugin owner) {}

    private volatile @NonNull Map<Key, Entry> entries = Map.of();
    private final @NonNull ConcurrentHashMap<String, CallbackEntry> callbacks =
            new ConcurrentHashMap<>();
    private final @NonNull AtomicLong generations = new AtomicLong();
    private final @NonNull Map<String, Set<String>> callbackOwners = new HashMap<>();
    // Stable activation strings record revocation without retaining plugin classes or loaders.
    private final @NonNull Set<String> revokedCallbackActivations = new HashSet<>();
    private final @NonNull BiPredicate<@NonNull String, @NonNull String> allowed;
    private final @NonNull ScopeResolver scopeResolver;

    /** Validates a caller-owned host grant and derives the provider-visible call context. */
    @FunctionalInterface
    public interface ScopeResolver {
        @NonNull ServiceCallContext resolve(
                @NonNull String callerId,
                @NonNull String providerId,
                @NonNull PluginScope required,
                PluginStorage.@NonNull Grant<?> grant)
                throws ServiceException;
    }

    /** Creates a registry whose visibility is governed by the given caller-to-owner predicate. */
    public PluginServiceRegistry(@NonNull BiPredicate<@NonNull String, @NonNull String> allowed) {
        this(
                allowed,
                (callerId, providerId, required, grant) -> {
                    throw new ServiceException(ServiceException.Code.UNAVAILABLE);
                });
    }

    /** Creates a registry with host-issued scope validation for scoped services. */
    public PluginServiceRegistry(
            @NonNull BiPredicate<@NonNull String, @NonNull String> allowed,
            @NonNull ScopeResolver scopeResolver) {
        this.allowed = allowed;
        this.scopeResolver = scopeResolver;
    }

    /** Atomically replaces the service directory after a plugin catalog transition. */
    // Provider handles are borrowed from the host plugin lifecycle, which owns their closure.
    @SuppressWarnings("resource")
    public synchronized void bind(
            @NonNull ContributionCatalog catalog, @NonNull List<ManagedPlugin> plugins) {
        Map<String, ManagedPlugin> owners = new HashMap<>();
        for (var plugin : plugins) owners.put(plugin.identity().id(), plugin);
        Map<Key, Entry> staged = new HashMap<>();
        for (var contribution : catalog.entries(StandardContributionPoints.SERVICES)) {
            var service = contribution.implementation();
            var owner = owners.get(contribution.source().namespace());
            if (owner == null) throw new IllegalArgumentException("Service owner is unavailable");
            Key key = new Key(service.name(), service.version());
            Entry previous = entries.get(key);
            long generation =
                    previous != null && previous.owner() == owner && previous.service() == service
                            ? previous.generation()
                            : generations.incrementAndGet();
            if (staged.putIfAbsent(key, new Entry(service, owner, generation)) != null)
                throw new IllegalArgumentException("Duplicate service name and version");
        }
        entries = Map.copyOf(staged);
    }

    /** Revokes one provider's registrations before its lifecycle and classloader are closed. */
    // Provider handles are borrowed from the host plugin lifecycle, which owns their closure.
    @SuppressWarnings("resource")
    public synchronized void revoke(@NonNull String providerId) {
        var activation = callbackOwners.remove(providerId);
        if (activation != null) revokedCallbackActivations.addAll(activation);
        var remaining = new HashMap<Key, Entry>();
        entries.forEach(
                (key, entry) -> {
                    if (!entry.owner().identity().id().equals(providerId))
                        remaining.put(key, entry);
                });
        entries = Map.copyOf(remaining);
        callbacks
                .entrySet()
                .removeIf(entry -> entry.getValue().owner().identity().id().equals(providerId));
    }

    /** Returns the service view authorized for the given calling plugin. */
    // Provider handles are borrowed from the host plugin lifecycle, which owns their closure.
    @SuppressWarnings("resource")
    public synchronized @NonNull PluginServices forPlugin(@NonNull ManagedPlugin caller) {
        callbackOwners
                .computeIfAbsent(caller.identity().id(), ignored -> new HashSet<>())
                .add(caller.bindingId());
        return view(caller);
    }

    /** Host adapters supply their own authorization before using this directory. */
    public @NonNull PluginServices forHost() {
        return view(null);
    }

    private @NonNull PluginServices view(ManagedPlugin caller) {
        return new PluginServices() {
            // Owner handles are registered by bind() and closed by the host plugin lifecycle.
            @SuppressWarnings("resource")
            private boolean visible(@NonNull Entry entry) {
                return entry.owner().state() == PluginState.ACTIVE
                        && allowed.test(
                                caller == null ? "" : caller.identity().id(),
                                entry.owner().identity().id());
            }

            // Owner handles are registered by bind() and closed by the host plugin lifecycle.
            @SuppressWarnings("resource")
            public @NonNull List<Descriptor> available() {
                return entries.values().stream()
                        .filter(this::visible)
                        .map(
                                entry ->
                                        new Descriptor(
                                                entry.service().name(),
                                                entry.service().version(),
                                                entry.owner().identity().id(),
                                                entry.service().scope()))
                        .sorted(
                                Comparator.comparing(Descriptor::name)
                                        .thenComparingInt(Descriptor::version))
                        .toList();
            }

            // Retained calls borrow the activation; only its host lifecycle may close it.
            @SuppressWarnings("resource")
            public @NonNull Optional<Handle> find(@NonNull String name, int version) {
                Key key = new Key(name, version);
                var entry = entries.get(key);
                if (entry == null || !visible(entry)) return Optional.empty();
                // Retained handles carry only a key and generation, never a provider object.
                long generation = entry.generation();
                Descriptor descriptor =
                        new Descriptor(
                                name,
                                version,
                                entry.owner().identity().id(),
                                entry.service().scope());
                return Optional.of(
                        new Handle() {
                            public @NonNull Descriptor descriptor() {
                                return descriptor;
                            }

                            public @NonNull JsonValue invoke(@NonNull JsonValue request)
                                    throws ServiceException {
                                Entry current = entries.get(key);
                                if (current == null
                                        || current.generation() != generation
                                        || !visible(current))
                                    throw new ServiceException(ServiceException.Code.UNAVAILABLE);
                                if (current.service().scope() != PluginScope.APPLICATION)
                                    throw new ServiceException(
                                            ServiceException.Code.INVALID_REQUEST);
                                return invokeService(
                                        caller,
                                        current,
                                        new ServiceCallContext(
                                                caller == null ? "" : caller.identity().id(),
                                                PluginScope.APPLICATION,
                                                new Scope.GlobalScope(),
                                                null),
                                        request);
                            }

                            public @NonNull JsonValue invoke(
                                    PluginStorage.@NonNull Grant<?> grant,
                                    @NonNull JsonValue request)
                                    throws ServiceException {
                                Entry current = entries.get(key);
                                if (current == null
                                        || current.generation() != generation
                                        || !visible(current)
                                        || caller == null
                                        || current.service().scope() == PluginScope.APPLICATION)
                                    throw new ServiceException(ServiceException.Code.UNAVAILABLE);
                                ServiceCallContext context =
                                        scopeResolver.resolve(
                                                caller.identity().id(),
                                                current.owner().identity().id(),
                                                current.service().scope(),
                                                grant);
                                return invokeService(caller, current, context, request);
                            }
                        });
            }

            public @NonNull CallbackRegistration registerCallback(@NonNull ServiceHandler handler) {
                if (caller == null) throw new IllegalStateException("Plugin caller is required");
                String id = UUID.randomUUID().toString();
                CallbackEntry registration = new CallbackEntry(handler, caller);
                synchronized (PluginServiceRegistry.this) {
                    var state = caller.state();
                    if ((state != PluginState.INITIALIZING
                                    && state != PluginState.INITIALIZED
                                    && state != PluginState.STARTING
                                    && state != PluginState.ACTIVE)
                            || revokedCallbackActivations.contains(caller.bindingId()))
                        throw new IllegalStateException("Plugin callback registration is closed");
                    callbacks.put(id, registration);
                }
                return new CallbackRegistration() {
                    public @NonNull String id() {
                        return id;
                    }

                    public void close() {
                        callbacks.remove(id, registration);
                    }
                };
            }

            // Retained calls borrow the activation; only its host lifecycle may close it.
            @SuppressWarnings("resource")
            public @NonNull Optional<CallbackHandle> findCallback(@NonNull String id) {
                CallbackEntry registration = callbacks.get(id);
                if (registration == null
                        || registration.owner().state() != PluginState.ACTIVE
                        || !allowed.test(
                                caller == null ? "" : caller.identity().id(),
                                registration.owner().identity().id())) return Optional.empty();
                String providerId = registration.owner().identity().id();
                return Optional.of(
                        new CallbackHandle() {
                            public @NonNull String providerId() {
                                return providerId;
                            }

                            public @NonNull JsonValue invoke(@NonNull JsonValue request)
                                    throws ServiceException {
                                CallbackEntry current = callbacks.get(id);
                                if (current == null
                                        || current.owner().state() != PluginState.ACTIVE
                                        || !allowed.test(
                                                caller == null ? "" : caller.identity().id(),
                                                current.owner().identity().id()))
                                    throw new ServiceException(ServiceException.Code.UNAVAILABLE);
                                return invokeAdmitted(
                                        caller,
                                        current.owner(),
                                        () -> invokeCallbackHandler(current.handler(), request));
                            }
                        });
            }
        };
    }

    private static @NonNull Outcome invokeCallbackHandler(
            @NonNull ServiceHandler handler, @NonNull JsonValue request) {
        try {
            return new Outcome(handler.invoke(request), null);
        } catch (ServiceException failure) {
            return new Outcome(null, failure);
        } catch (Exception failure) {
            return new Outcome(null, new ServiceException(ServiceException.Code.FAILED));
        }
    }

    // Owner handles are registered by bind() and closed by the host plugin lifecycle.
    @SuppressWarnings("resource")
    private static @NonNull JsonValue invokeService(
            ManagedPlugin caller,
            @NonNull Entry entry,
            @NonNull ServiceCallContext context,
            @NonNull JsonValue request)
            throws ServiceException {
        return invokeAdmitted(
                caller, entry.owner(), () -> invokeHandler(entry.service(), context, request));
    }

    private static @NonNull JsonValue invokeAdmitted(
            ManagedPlugin caller,
            @NonNull ManagedPlugin owner,
            ManagedPlugin.@NonNull Operation<Outcome> body)
            throws ServiceException {
        Outcome outcome;
        try {
            ManagedPlugin.Operation<Outcome> operation = () -> owner.execute(body);
            outcome = caller == null ? operation.run() : caller.execute(operation);
        } catch (PluginFailure failure) {
            throw new ServiceException(ServiceException.Code.UNAVAILABLE);
        }
        if (outcome.failure() != null) throw outcome.failure();
        var value = outcome.value();
        if (value == null) throw new ServiceException(ServiceException.Code.FAILED);
        return value;
    }

    private static @NonNull Outcome invokeHandler(
            @NonNull PluginService service,
            @NonNull ServiceCallContext context,
            @NonNull JsonValue request) {
        try {
            return new Outcome(service.invoke(context, request), null);
        } catch (ServiceException failure) {
            return new Outcome(null, failure);
        } catch (Exception failure) {
            return new Outcome(null, new ServiceException(ServiceException.Code.FAILED));
        }
    }
}
