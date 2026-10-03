package top.focess.veto.integration.plugins.storage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.plugin.PluginScope;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/** In-memory configuration fixture; each bound plugin has independent stores and scope tokens. */
public final class ConfigurationStorageFixture implements PluginStorageFactory {
    public @NonNull PluginStorage bind(@NonNull ManagedPlugin plugin) {
        return new Storage();
    }

    public @NonNull String authorizeSession(
            @NonNull PluginStorage storage,
            PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> scope) {
        storage.session(scope);
        return scope.scope().owner();
    }

    public @NonNull String authorizeUser(
            @NonNull PluginStorage storage,
            PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> scope) {
        storage.user(scope);
        return scope.scope().owner();
    }

    public PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> transferUser(
            @NonNull PluginStorage caller,
            PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> scope,
            @NonNull PluginStorage provider) {
        throw new UnsupportedOperationException("Fixture has no user scopes");
    }

    public PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> transferSession(
            @NonNull PluginStorage caller,
            PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> scope,
            @NonNull PluginStorage provider) {
        throw new UnsupportedOperationException("Fixture has no cross-plugin scopes");
    }

    private static final class Storage implements PluginStorage {
        private final @NonNull Map<@NonNull String, @NonNull Grant<Scope.@NonNull SessionScope>>
                scopes = new HashMap<>();
        private final @NonNull Map<@NonNull String, @NonNull Store> stores = new HashMap<>();

        public @NonNull Store application() {
            throw new UnsupportedOperationException("Fixture supports session configuration only");
        }

        public @NonNull Store user(@NonNull Grant<Scope.@NonNull UserScope> scope) {
            throw new UnsupportedOperationException("Fixture supports session configuration only");
        }

        public synchronized @NonNull Store session(
                @NonNull Grant<Scope.@NonNull SessionScope> scope) {
            var issued = scopes.get(scope.scope().session());
            if (issued == null || !scope.equals(issued))
                throw new SecurityException("Unknown fixture scope");
            return stores.computeIfAbsent(scope.scope().session(), ignored -> new MemoryStore());
        }

        public synchronized @NonNull Page<@NonNull Grant<?>> scopes(
                @NonNull PluginScope kind, String cursor, int limit) {
            if (cursor != null) throw new IllegalArgumentException("Unknown cursor");
            return new Page<>(
                    kind == PluginScope.SESSION ? List.copyOf(scopes.values()) : List.of(), null);
        }

        public synchronized @NonNull Grant<Scope.@NonNull SessionScope> currentSession() {
            var invocation = PluginInvocationContext.current();
            var call = ToolCallContextHolder.get();
            String owner =
                    invocation == null ? (call == null ? null : call.owner()) : invocation.owner;
            var callSession = call == null ? null : call.sessionId();
            String session =
                    invocation == null
                            ? (callSession == null ? null : callSession.toString())
                            : invocation.session;
            if (owner == null || session == null)
                throw new SecurityException("No authenticated fixture invocation");
            var scope =
                    scopes.computeIfAbsent(
                            session,
                            id ->
                                    new Grant<>(
                                            UUID.randomUUID().toString(),
                                            new Scope.SessionScope(owner, id)));
            if (!scope.scope().owner().equals(owner))
                throw new SecurityException("Fixture owner mismatch");
            return scope;
        }

        public @NonNull Grant<Scope.@NonNull UserScope> currentUser() {
            throw new UnsupportedOperationException("Fixture supports session configuration only");
        }
    }

    private static final class MemoryStore implements PluginStorage.Store {
        private final @NonNull Map<@NonNull String, PluginStorage.@NonNull Entry> rows =
                new HashMap<>();

        public synchronized @NonNull Optional<PluginStorage.@NonNull Entry> get(
                @NonNull String key) {
            return Optional.ofNullable(rows.get(key));
        }

        public synchronized PluginStorage.@NonNull Page<PluginStorage.@NonNull Entry> list(
                @NonNull String prefix, String cursor, int limit) {
            if (cursor != null) throw new IllegalArgumentException("Unknown cursor");
            var found = rows.values().stream().filter(row -> row.key().startsWith(prefix)).toList();
            if (found.size() > limit)
                throw new IllegalStateException("Fixture page capacity exceeded");
            return new PluginStorage.Page<>(found, null);
        }

        public synchronized PluginStorage.@NonNull Entry put(
                @NonNull String key, String revision, PluginStorage.@NonNull Document document) {
            var old = rows.get(key);
            if (!Objects.equals(old == null ? null : old.revision(), revision))
                throw new PluginStorage.Conflict();
            var entry = new PluginStorage.Entry(key, UUID.randomUUID().toString(), document);
            rows.put(key, entry);
            return entry;
        }

        public synchronized void delete(@NonNull String key, @NonNull String revision) {
            var old = rows.get(key);
            if (old == null || !old.revision().equals(revision)) throw new PluginStorage.Conflict();
            rows.remove(key);
        }
    }
}
