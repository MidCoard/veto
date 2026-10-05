package top.focess.veto.builtin.monitor;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginScope;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.storage.PluginStorage;

/** API-only host fixture; host authorization and durable transactions are tested by core. */
final class MemoryPluginStorage implements PluginStorage {
    private final @NonNull Map<@NonNull String, @NonNull Store> stores = new HashMap<>();

    @Override
    public @NonNull Store application() {
        return stores.computeIfAbsent("app", ignored -> new MemoryStore());
    }

    @Override
    public @NonNull Store user(@NonNull Grant<Scope.@NonNull UserScope> grant) {
        return stores.computeIfAbsent(
                "user/" + grant.scope().userId(), ignored -> new MemoryStore());
    }

    @Override
    public @NonNull Store session(@NonNull Grant<Scope.@NonNull SessionScope> grant) {
        return stores.computeIfAbsent(grant.scope().session(), ignored -> new MemoryStore());
    }

    @Override
    public @NonNull Page<@NonNull Grant<?>> scopes(
            @NonNull PluginScope kind, String cursor, int limit) {
        return new Page<>(
                List.of(
                        new Grant<>(
                                "s",
                                new Scope.SessionScope(
                                        UUID.fromString("f976584f-7d69-5125-869a-6897e8b4a84e"),
                                        "session")),
                        new Grant<>(
                                "c",
                                new Scope.SessionScope(
                                        UUID.fromString("f976584f-7d69-5125-869a-6897e8b4a84e"),
                                        "closed")),
                        new Grant<>(
                                "k",
                                new Scope.SessionScope(
                                        UUID.fromString("f976584f-7d69-5125-869a-6897e8b4a84e"),
                                        "kept"))),
                null);
    }

    @Override
    public @NonNull Grant<Scope.@NonNull SessionScope> currentSession() {
        return new Grant<>(
                "s",
                new Scope.SessionScope(
                        UUID.fromString("f976584f-7d69-5125-869a-6897e8b4a84e"), "session"));
    }

    @Override
    public @NonNull Grant<Scope.@NonNull UserScope> currentUser() {
        return new Grant<>(
                "u", new Scope.UserScope(UUID.fromString("f976584f-7d69-5125-869a-6897e8b4a84e")));
    }

    private static final class MemoryStore implements Store {
        private final @NonNull Map<@NonNull String, @NonNull Entry> entries = new HashMap<>();

        @Override
        public synchronized @NonNull Optional<@NonNull Entry> get(@NonNull String key) {
            return Optional.ofNullable(entries.get(key));
        }

        @Override
        public synchronized @NonNull Page<@NonNull Entry> list(
                @NonNull String prefix, String cursor, int limit) {
            var values =
                    entries.values().stream()
                            .filter(
                                    entry ->
                                            entry.key().startsWith(prefix)
                                                    && (cursor == null
                                                            || entry.key().compareTo(cursor) > 0))
                            .sorted(Comparator.comparing(Entry::key))
                            .toList();
            int count = Math.min(limit == 0 ? 50 : limit, values.size());
            return new Page<>(
                    values.subList(0, count),
                    count < values.size() ? values.get(count - 1).key() : null);
        }

        @Override
        public synchronized @NonNull Entry put(
                @NonNull String key, String expected, @NonNull Document document) {
            Entry old = entries.get(key);
            if (!Objects.equals(expected, old == null ? null : old.revision()))
                throw new Conflict();
            Entry next = new Entry(key, UUID.randomUUID().toString(), document);
            entries.put(key, next);
            return next;
        }

        @Override
        public synchronized void delete(@NonNull String key, @NonNull String expected) {
            Entry old = entries.get(key);
            if (old == null || !expected.equals(old.revision())) throw new Conflict();
            entries.remove(key);
        }
    }
}
