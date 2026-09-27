package top.focess.veto.plugin.runtime;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.ContributionCatalog;
import top.focess.veto.api.plugin.service.PluginServices;
import top.focess.veto.api.plugin.service.ServiceException;
import top.focess.veto.api.plugin.service.ServiceRegistration;

/** Atomically bound service directory; implementation objects never escape to consumers. */
public final class PluginServiceRegistry {
    private record Key(@NonNull String name, int version) {}

    private record Entry(
            @NonNull ServiceRegistration service,
            @NonNull PluginLifecycle owner,
            long generation) {}

    private record Outcome(@Nullable JsonValue value, @Nullable ServiceException failure) {}

    private volatile @NonNull Map<Key, Entry> entries = Map.of();
    private final @NonNull AtomicLong generations = new AtomicLong();
    private final @NonNull BiPredicate<@NonNull String, @NonNull String> allowed;

    /** Creates a registry whose visibility is governed by the given caller-to-owner predicate. */
    public PluginServiceRegistry(@NonNull BiPredicate<@NonNull String, @NonNull String> allowed) {
        this.allowed = allowed;
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
    }

    /** Returns the service view authorized for the given calling plugin. */
    public @NonNull PluginServices forPlugin(@NonNull PluginLifecycle caller) {
        return view(caller);
    }

    /** Host adapters supply their own authorization before using this directory. */
    public @NonNull PluginServices forHost() {
        return view(null);
    }

    private @NonNull PluginServices view(@Nullable PluginLifecycle caller) {
        return new PluginServices() {
            // Owner handles are registered by bind() and closed by the host plugin lifecycle.
            @SuppressWarnings("resource")
            private boolean visible(Entry entry) {
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
                                                entry.owner().identity().id()))
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
                        new Descriptor(name, version, entry.owner().identity().id());
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
                                return invokeService(caller, current, request);
                            }
                        });
            }
        };
    }

    // Owner handles are registered by bind() and closed by the host plugin lifecycle.
    @SuppressWarnings("resource")
    private static @NonNull JsonValue invokeService(
            @Nullable PluginLifecycle caller, @NonNull Entry entry, @NonNull JsonValue request)
            throws ServiceException {
        Outcome outcome;
        try {
            PluginLifecycle.Operation<Outcome> operation =
                    () -> entry.owner().execute(() -> invokeHandler(entry.service(), request));
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
            @NonNull ServiceRegistration service, @NonNull JsonValue request) {
        try {
            return new Outcome(service.handler().invoke(request), null);
        } catch (ServiceException failure) {
            return new Outcome(null, failure);
        } catch (Exception failure) {
            return new Outcome(null, new ServiceException(ServiceException.Code.FAILED));
        }
    }
}
