package top.focess.veto.builtin.monitor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.plugin.storage.PluginStorage;

/** API-only host fixture; host authorization and durable transactions are tested by core. */
@NullMarked
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
final class MemoryPluginStorage implements PluginStorage {
    private final Map<String, Store> stores = new HashMap<>();

    @Override
    public Store application() {
        return stores.computeIfAbsent("app", ignored -> new MemoryStore());
    }

    @Override
    public Store user(UserScope scope) {
        return stores.computeIfAbsent("user/" + scope.userId(), ignored -> new MemoryStore());
    }

    @Override
    public Store session(SessionScope scope) {
        return stores.computeIfAbsent(scope.sessionId(), ignored -> new MemoryStore());
    }

    @Override
    public Page<Scope> scopes(Kind kind, @Nullable String cursor, int limit) {
        return new Page<>(
                List.of(
                        new SessionScope("s", "u", "session"),
                        new SessionScope("c", "u", "closed"),
                        new SessionScope("k", "u", "kept")),
                null);
    }

    @Override
    public SessionScope currentSession() {
        return new SessionScope("s", "u", "session");
    }

    @Override
    public UserScope currentUser() {
        return new UserScope("u", "u");
    }

    private static final class MemoryStore implements Store {
        private final Map<String, Entry> entries = new HashMap<>();

        @Override
        public synchronized Optional<Entry> get(String key) {
            return Optional.ofNullable(entries.get(key));
        }

        @Override
        public synchronized Page<Entry> list(String prefix, @Nullable String cursor, int limit) {
            var values =
                    entries.values().stream()
                            .filter(
                                    entry ->
                                            entry.key().startsWith(prefix)
                                                    && (cursor == null
                                                            || entry.key().compareTo(cursor) > 0))
                            .sorted(java.util.Comparator.comparing(Entry::key))
                            .toList();
            int count = Math.min(limit == 0 ? 50 : limit, values.size());
            return new Page<>(
                    values.subList(0, count),
                    count < values.size() ? values.get(count - 1).key() : null);
        }

        @Override
        public synchronized Entry put(String key, @Nullable String expected, Document document) {
            Entry old = entries.get(key);
            if (!Objects.equals(expected, old == null ? null : old.revision()))
                throw new Conflict();
            Entry next = new Entry(key, UUID.randomUUID().toString(), document);
            entries.put(key, next);
            return next;
        }

        @Override
        public synchronized void delete(String key, String expected) {
            Entry old = entries.get(key);
            if (old == null || !expected.equals(old.revision())) throw new Conflict();
            entries.remove(key);
        }
    }
}
