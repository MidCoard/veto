package top.focess.veto.plugin.runtime;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.service.PluginService;
import top.focess.veto.api.plugin.service.PluginServices;
import top.focess.veto.api.plugin.service.ServiceCallContext;
import top.focess.veto.api.plugin.service.ServiceException;
import top.focess.veto.api.plugin.service.ServiceHandler;
import top.focess.veto.api.plugin.PluginScope;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.storage.PluginStorage;

/** Atomically bound service directory; implementation objects never escape to consumers. */
public final class PluginServiceRegistry {
    private record Key(@NonNull String name, int version) {}

    private record Entry(
            @NonNull PluginService service, @NonNull PluginLifecycle owner, long generation) {}

    private record Outcome(JsonValue value, ServiceException failure) {}

    private record CallbackEntry(@NonNull ServiceHandler handler, @NonNull PluginLifecycle owner) {}

    private volatile @NonNull Map<Key, Entry> entries = Map.of();
    private final @NonNull ConcurrentHashMap<String, CallbackEntry> callbacks =
            new ConcurrentHashMap<>();
    private final @NonNull AtomicLong generations = new AtomicLong();
    private final @NonNull BiPredicate<@NonNull String, @NonNull String> allowed;
    private final @NonNull ScopeResolver scopeResolver;

    /** Validates a caller-owned host scope and derives the provider-visible call context. */
    @FunctionalInterface
    public interface ScopeResolver {
        @NonNull ServiceCallContext resolve(
                @NonNull String callerId,
                @NonNull String providerId,
                @NonNull PluginScope required,
                PluginStorage.@NonNull Scope scope)
                throws ServiceException;
    }

    /** Creates a registry whose visibility is governed by the given caller-to-owner predicate. */
    public PluginServiceRegistry(@NonNull BiPredicate<@NonNull String, @NonNull String> allowed) {
        this(
                allowed,
                (callerId, providerId, required, scope) -> {
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
    public synchronized void bind(
            @NonNull ContributionCatalog catalog, @NonNull List<PluginLifecycle> plugins) {
        Map<String, PluginLifecycle> owners = new HashMap<>();
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
    public synchronized void revoke(@NonNull String providerId) {
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
    public @NonNull PluginServices forPlugin(@NonNull PluginLifecycle caller) {
        return view(caller);
    }

    /** Host adapters supply their own authorization before using this directory. */
    public @NonNull PluginServices forHost() {
        return view(null);
    }

    private @NonNull PluginServices view(PluginLifecycle caller) {
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
                                    PluginStorage.@NonNull Scope scope, @NonNull JsonValue request)
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
                                                scope);
                                return invokeService(caller, current, context, request);
                            }
                        });
            }

            public @NonNull CallbackRegistration registerCallback(@NonNull ServiceHandler handler) {
                if (caller == null) throw new IllegalStateException("Plugin caller is required");
                String id = UUID.randomUUID().toString();
                CallbackEntry registration = new CallbackEntry(handler, caller);
                callbacks.put(id, registration);
                return new CallbackRegistration() {
                    public @NonNull String id() {
                        return id;
                    }

                    public void close() {
                        callbacks.remove(id, registration);
                    }
                };
            }

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
                                try {
                                    PluginLifecycle.Operation<Outcome> operation =
                                            () ->
                                                    current.owner()
                                                            .execute(
                                                                    () ->
                                                                            invokeCallbackHandler(
                                                                                    current
                                                                                            .handler(),
                                                                                    request));
                                    Outcome outcome =
                                            caller == null
                                                    ? operation.run()
                                                    : caller.execute(operation);
                                    if (outcome.failure() != null) throw outcome.failure();
                                    JsonValue result = outcome.value();
                                    if (result == null)
                                        throw new ServiceException(ServiceException.Code.FAILED);
                                    return result;
                                } catch (PluginFailure failure) {
                                    throw new ServiceException(ServiceException.Code.UNAVAILABLE);
                                }
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
            PluginLifecycle caller,
            @NonNull Entry entry,
            @NonNull ServiceCallContext context,
            @NonNull JsonValue request)
            throws ServiceException {
        Outcome outcome;
        try {
            PluginLifecycle.Operation<Outcome> operation =
                    () ->
                            entry.owner()
                                    .execute(
                                            () -> invokeHandler(entry.service(), context, request));
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
