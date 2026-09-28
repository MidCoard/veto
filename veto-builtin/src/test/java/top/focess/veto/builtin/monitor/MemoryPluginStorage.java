package top.focess.veto.builtin.monitor;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.storage.PluginStorage;

/** API-only host fixture; host authorization and durable transactions are tested by core. */
final class MemoryPluginStorage implements PluginStorage {
    private final @NonNull Map<@NonNull String, @NonNull Store> stores = new HashMap<>();

    @Override
    public @NonNull Store application() {
        return stores.computeIfAbsent("app", ignored -> new MemoryStore());
    }

    @Override
    public @NonNull Store user(@NonNull UserScope scope) {
        return stores.computeIfAbsent("user/" + scope.userId(), ignored -> new MemoryStore());
    }

    @Override
    public @NonNull Store session(@NonNull SessionScope scope) {
        return stores.computeIfAbsent(scope.sessionId(), ignored -> new MemoryStore());
    }

    @Override
    public @NonNull Page<@NonNull Scope> scopes(@NonNull Kind kind, String cursor, int limit) {
        return new Page<>(
                List.of(
                        new SessionScope("s", "u", "session"),
                        new SessionScope("c", "u", "closed"),
                        new SessionScope("k", "u", "kept")),
                null);
    }

    @Override
    public @NonNull SessionScope currentSession() {
        return new SessionScope("s", "u", "session");
    }

    @Override
    public @NonNull UserScope currentUser() {
        return new UserScope("u", "u");
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
