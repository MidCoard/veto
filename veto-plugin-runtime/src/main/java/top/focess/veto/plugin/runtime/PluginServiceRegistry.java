package top.focess.veto.plugin.runtime;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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

    private record Entry(@NonNull ServiceRegistration service, @NonNull ManagedPlugin owner) {}

    private record Outcome(@Nullable JsonValue value, @Nullable ServiceException failure) {}

    private volatile @NonNull Map<Key, Entry> entries = Map.of();
    private boolean bound;
    private final @NonNull BiPredicate<@NonNull String, @NonNull String> allowed;

    public PluginServiceRegistry(@NonNull BiPredicate<@NonNull String, @NonNull String> allowed) {
        this.allowed = allowed;
    }

    public synchronized void bind(
            @NonNull ContributionCatalog catalog, @NonNull List<ManagedPlugin> plugins) {
        if (bound) throw new IllegalStateException("Services already bound");
        Map<String, ManagedPlugin> owners = new HashMap<>();
        for (var plugin : plugins) owners.put(plugin.identity().id(), plugin);
        Map<Key, Entry> staged = new HashMap<>();
        for (var contribution : catalog.entries(StandardContributionPoints.SERVICES)) {
            var service = contribution.implementation();
            var owner = owners.get(contribution.source().namespace());
            if (owner == null) throw new IllegalArgumentException("Service owner is unavailable");
            if (staged.putIfAbsent(
                            new Key(service.name(), service.version()), new Entry(service, owner))
                    != null)
                throw new IllegalArgumentException("Duplicate service name and version");
        }
        entries = Map.copyOf(staged);
        bound = true;
    }

    public @NonNull PluginServices forPlugin(@NonNull ManagedPlugin caller) {
        return view(caller);
    }

    /** Host adapters supply their own authorization before using this directory. */
    public @NonNull PluginServices forHost() {
        return view(null);
    }

    private @NonNull PluginServices view(@Nullable ManagedPlugin caller) {
        return new PluginServices() {
            private boolean visible(Entry entry) {
                return allowed.test(
                        caller == null ? "" : caller.identity().id(),
                        entry.owner().identity().id());
            }

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
                var entry = entries.get(new Key(name, version));
                if (entry == null || !visible(entry)) return Optional.empty();
                return Optional.of(
                        new Handle() {
                            public @NonNull Descriptor descriptor() {
                                return new Descriptor(name, version, entry.owner().identity().id());
                            }

                            public @NonNull JsonValue invoke(@NonNull JsonValue request)
                                    throws ServiceException {
                                if (!visible(entry))
                                    throw new ServiceException(ServiceException.Code.UNAVAILABLE);
                                return invokeService(caller, entry, request);
                            }
                        });
            }
        };
    }

    private static @NonNull JsonValue invokeService(
            @Nullable ManagedPlugin caller, @NonNull Entry entry, @NonNull JsonValue request)
            throws ServiceException {
        Outcome outcome;
        try {
            ManagedPlugin.Operation<Outcome> operation =
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
