package top.focess.veto.plugin.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.AgentInbox;
import top.focess.veto.api.plugin.contract.PluginFailure;

/**
 * Namespaces durable inbox identities and admits callbacks through their owning lifecycle.
 * Observations returned by pending retain their exact source/activation while the observation is
 * live. Unknown or copied observations cannot be acknowledged; restart recovery repolls pending
 * work, preserving durable keys while binding the new observation to its current owner. Pending and
 * callbacks may overlap; source implementations own their concurrency contract. Only weak-route
 * bookkeeping is locked, never source callbacks or selection resolution.
 */
public final class CompositeAgentInbox implements AgentInbox {
    /** An inbox source paired with its owning plugin and identity namespace. */
    public record Entry(
            @NonNull String id, @NonNull ManagedPlugin plugin, @NonNull AgentInbox source) {}

    private final @NonNull Supplier<@NonNull List<@NonNull Entry>> entries;
    private final @NonNull ReferenceQueue<Observation> abandoned = new ReferenceQueue<>();
    private final @NonNull Map<ObservationReference, Entry> routes = new HashMap<>();

    // Observation records have structural equality; routing must distinguish equal live objects.
    private static final class ObservationReference extends WeakReference<Observation> {
        private final int hash;

        private ObservationReference(
                @NonNull Observation value, @NonNull ReferenceQueue<Observation> queue) {
            super(value, queue);
            hash = System.identityHashCode(value);
        }

        private ObservationReference(@NonNull Observation value) {
            super(value);
            hash = System.identityHashCode(value);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            var value = get();
            return value != null
                    && other instanceof ObservationReference reference
                    && value == reference.get();
        }
    }

    // Called only while holding this composite's monitor.
    private void discardAbandonedRoutes() {
        for (var reference = abandoned.poll(); reference != null; reference = abandoned.poll())
            routes.remove(reference);
    }

    private synchronized void bind(@NonNull Observation value, @NonNull Entry entry) {
        discardAbandonedRoutes();
        routes.put(new ObservationReference(value, abandoned), entry);
    }

    private synchronized Entry bound(@NonNull Observation value) {
        discardAbandonedRoutes();
        return routes.get(new ObservationReference(value));
    }

    /** Creates a composite that reads its namespaced entries from the given supplier. */
    public CompositeAgentInbox(@NonNull Supplier<@NonNull List<@NonNull Entry>> entries) {
        this.entries = entries;
    }

    private static @NonNull Observation identity(
            @NonNull Observation value, @NonNull String id, @NonNull String continuation) {
        return new Observation(
                id,
                value.requestId(),
                value.content(),
                value.occurredAt(),
                value.topic(),
                value.attributes(),
                continuation);
    }

    private static @NonNull String continuation(
            @NonNull String namespace, @NonNull Observation value) {
        String key = value.continuationId();
        return "plugin-work:"
                + namespace.length()
                + ":"
                + namespace
                + ":"
                + (key == null ? value.id() : key);
    }

    private static @NonNull String rawContinuation(
            @NonNull String namespace, @NonNull Observation value) {
        String key = value.continuationId();
        String prefix = "plugin-work:" + namespace.length() + ":" + namespace + ":";
        if (key == null || !key.startsWith(prefix))
            throw new SecurityException("Unbound plugin work identity");
        return key.substring(prefix.length());
    }

    // The plugin handle is owned by the entries supplier and closed by its lifecycle owner.
    // The @NonNull bound is required so T satisfies Operation<T>.
    @SuppressWarnings({"resource", "NullableProblems"})
    private static <T extends @NonNull Object> T invoke(
            @NonNull Entry entry, ManagedPlugin.@NonNull Operation<T> action) {
        try {
            return entry.plugin().execute(action);
        } catch (PluginFailure failure) {
            throw new IllegalStateException("Plugin work unavailable", failure);
        }
    }

    @Override
    public @NonNull List<@NonNull Observation> pending(@NonNull InboxContext scope) {
        return entries.get().stream()
                .flatMap(
                        entry ->
                                invoke(entry, () -> entry.source().pending(scope)).stream()
                                        .map(
                                                value -> {
                                                    var observation =
                                                            identity(
                                                                    value,
                                                                    entry.id() + "/" + value.id(),
                                                                    continuation(
                                                                            entry.id(), value));
                                                    bind(observation, entry);
                                                    return observation;
                                                }))
                .toList();
    }

    private void notify(
            @NonNull Observation observation,
            @NonNull BiConsumer<@NonNull AgentInbox, @NonNull Observation> action) {
        var selected = entries.get();
        var owner = bound(observation);
        if (owner != null) {
            boolean available =
                    selected.stream()
                            .anyMatch(
                                    entry ->
                                            entry.id().equals(owner.id())
                                                    && entry.plugin() == owner.plugin()
                                                    && entry.source() == owner.source());
            if (!available) throw new IllegalStateException("Plugin work source unavailable");
            invoke(
                    owner,
                    () -> {
                        action.accept(
                                owner.source(),
                                identity(
                                        observation,
                                        observation.id().substring(owner.id().length() + 1),
                                        rawContinuation(owner.id(), observation)));
                        return true;
                    });
            return;
        }
        throw new IllegalStateException("Plugin work source unavailable");
    }

    @Override
    public void started(@NonNull InboxContext scope, @NonNull Observation value) {
        notify(value, (source, raw) -> source.started(scope, raw));
    }

    @Override
    public void completed(
            @NonNull InboxContext scope, @NonNull Observation value, boolean success) {
        notify(value, (source, raw) -> source.completed(scope, raw, success));
    }

    @Override
    public void cancelled(@NonNull InboxContext scope, @NonNull Observation value) {
        notify(value, (source, raw) -> source.cancelled(scope, raw));
    }
}
