package top.focess.veto.extension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

/**
 * Shared registration substrate for builtins and plugins. It never invokes contributions, grants
 * authority, starts plugins or publishes a catalog into a running application. The host owns those
 * actions after successful validation/startup.
 */
public final class ExtensionCatalog {
    private final @NonNull Map<ExtensionId, Definition<?>> definitions;
    private final @NonNull List<Staged> entries;

    private ExtensionCatalog(
            @NonNull Map<ExtensionId, Definition<?>> definitions, @NonNull List<Staged> entries) {
        this.definitions = Map.copyOf(definitions);
        this.entries = List.copyOf(entries);
    }

    public <T extends @NonNull Object> @NonNull List<ExtensionEntry<T>> entries(
            @NonNull ExtensionPoint<T> point) {
        Definition<?> definition = definitions.get(point.id());
        if (definition == null || !definition.point().equals(point)) throw invalid();
        List<ExtensionEntry<T>> result = new ArrayList<>();
        for (Staged entry : entries) {
            if (entry.contribution().point().equals(point)) {
                result.add(
                        new ExtensionEntry<>(
                                entry.id(),
                                entry.source(),
                                point.contract().cast(entry.contribution().implementation())));
            }
        }
        return List.copyOf(result);
    }

    private record Definition<T extends @NonNull Object>(
            @NonNull ExtensionPoint<T> point, @NonNull Consumer<T> validator) {
        void validate(@NonNull ExtensionContribution<?> contribution) {
            if (!point.equals(contribution.point())) throw invalid();
            validator.accept(point.contract().cast(contribution.implementation()));
        }
    }

    private record Staged(
            @NonNull ExtensionId id,
            @NonNull ExtensionSource source,
            @NonNull ExtensionContribution<?> contribution) {}

    private static @NonNull IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid extension registration");
    }

    /** Single-threaded bootstrap only. Do not expose this builder to extension implementations. */
    public static final class Builder {
        private final Map<ExtensionId, Definition<?>> definitions = new LinkedHashMap<>();
        private final Map<ExtensionId, Staged> staged = new LinkedHashMap<>();
        private final Set<String> sources = new HashSet<>();
        private final Set<ExtensionId> required = new HashSet<>();
        private boolean frozen;
        private final List<Consumer<ExtensionCatalog>> catalogValidators = new ArrayList<>();

        /** Host-owned cross-point validation runs before any snapshot is returned. */
        public @NonNull Builder validateWith(@NonNull Consumer<ExtensionCatalog> validator) {
            mutable();
            catalogValidators.add(validator);
            return this;
        }

        public <T extends @NonNull Object> @NonNull Builder define(
                @NonNull ExtensionPoint<T> point, @NonNull Consumer<T> validator) {
            mutable();
            if (definitions.size() >= 256 || definitions.containsKey(point.id())) throw invalid();
            definitions.put(point.id(), new Definition<>(point, validator));
            return this;
        }

        /** Requires at least one contribution. Exact required providers remain host policy. */
        public @NonNull Builder require(@NonNull ExtensionPoint<?> point) {
            mutable();
            Definition<?> definition = definitions.get(point.id());
            if (definition == null || !definition.point().equals(point)) throw invalid();
            required.add(point.id());
            return this;
        }

        /** Atomic per-source staging: a rejected batch contributes nothing. */
        public @NonNull Builder stage(
                @NonNull ExtensionSource source,
                @NonNull List<? extends ExtensionContribution<?>> contributions) {
            mutable();
            if (sources.contains(source.namespace()) || staged.size() + contributions.size() > 4096)
                throw invalid();
            Map<ExtensionId, Staged> batch = new LinkedHashMap<>();
            for (ExtensionContribution<?> contribution : contributions) {
                Definition<?> definition = definitions.get(contribution.point().id());
                if (definition == null) throw invalid();
                definition.validate(contribution);
                ExtensionId id = source.qualify(contribution.localId());
                if (staged.containsKey(id) || batch.containsKey(id)) throw invalid();
                batch.put(id, new Staged(id, source, contribution));
            }
            staged.putAll(batch);
            sources.add(source.namespace());
            return this;
        }

        /** Discards a failed optional startup before snapshot creation. */
        public @NonNull Builder discard(@NonNull String sourceNamespace) {
            mutable();
            staged.values().removeIf(entry -> entry.source().namespace().equals(sourceNamespace));
            sources.remove(sourceNamespace);
            return this;
        }

        /** Checks cardinality, required points and ordering before freezing the builder. */
        public @NonNull ExtensionCatalog freeze() {
            mutable();
            List<Staged> ordered = new ArrayList<>();
            for (Definition<?> definition : definitions.values()) {
                ExtensionPoint<?> point = definition.point();
                List<Staged> group =
                        staged.values().stream()
                                .filter(entry -> entry.contribution().point().equals(point))
                                .toList();
                if ((point.cardinality() == ExtensionPoint.Cardinality.SINGLE && group.size() > 1)
                        || (required.contains(point.id()) && group.isEmpty())) throw invalid();
                ordered.addAll(sort(group));
            }
            ExtensionCatalog catalog = new ExtensionCatalog(definitions, ordered);
            for (var validator : catalogValidators) validator.accept(catalog);
            frozen = true;
            return catalog;
        }

        private static @NonNull List<Staged> sort(@NonNull List<Staged> group) {
            Map<ExtensionId, Staged> nodes = new HashMap<>();
            Map<ExtensionId, Set<ExtensionId>> edges = new HashMap<>();
            Map<ExtensionId, Integer> degree = new HashMap<>();
            for (Staged entry : group) {
                nodes.put(entry.id(), entry);
                edges.put(entry.id(), new HashSet<>());
                degree.put(entry.id(), 0);
            }
            for (Staged entry : group) {
                for (ExtensionId target : entry.contribution().before())
                    edge(entry.id(), target, edges, degree);
                for (ExtensionId target : entry.contribution().after())
                    edge(target, entry.id(), edges, degree);
            }
            PriorityQueue<ExtensionId> ready = new PriorityQueue<>();
            for (var entry : degree.entrySet())
                if (entry.getValue() == 0) ready.add(entry.getKey());
            List<Staged> result = new ArrayList<>();
            while (!ready.isEmpty()) {
                ExtensionId id = ready.remove();
                Staged entry = nodes.get(id);
                Set<ExtensionId> targets = edges.get(id);
                if (entry == null || targets == null) throw invalid();
                result.add(entry);
                for (ExtensionId target : targets) {
                    Integer previous = degree.get(target);
                    if (previous == null) throw invalid();
                    int remaining = previous - 1;
                    degree.put(target, remaining);
                    if (remaining == 0) ready.add(target);
                }
            }
            if (result.size() != group.size()) throw invalid();
            return result;
        }

        private static void edge(
                @NonNull ExtensionId from,
                @NonNull ExtensionId to,
                @NonNull Map<ExtensionId, Set<ExtensionId>> edges,
                @NonNull Map<ExtensionId, Integer> degree) {
            Set<ExtensionId> targets = edges.get(from);
            Integer previous = degree.get(to);
            if (from.equals(to) || targets == null || previous == null) throw invalid();
            if (targets.add(to)) degree.put(to, previous + 1);
        }

        private void mutable() {
            if (frozen) throw new IllegalStateException("Extension catalog already frozen");
        }
    }
}
