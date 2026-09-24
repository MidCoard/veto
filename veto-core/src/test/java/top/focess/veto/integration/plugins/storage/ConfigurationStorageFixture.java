package top.focess.veto.integration.plugins.storage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/** In-memory configuration fixture; each bound plugin has independent stores and scope tokens. */
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public final class ConfigurationStorageFixture implements PluginStorageFactory {
    public PluginStorage bind(ManagedPlugin plugin) {
        return new Storage();
    }

    public String authorizeSession(PluginStorage storage, PluginStorage.SessionScope scope) {
        storage.session(scope);
        return scope.userId();
    }

    private static final class Storage implements PluginStorage {
        private final Map<String, SessionScope> scopes = new HashMap<>();
        private final Map<String, Store> stores = new HashMap<>();

        public Store application() {
            throw new UnsupportedOperationException("Fixture supports session configuration only");
        }

        public Store user(UserScope scope) {
            throw new UnsupportedOperationException("Fixture supports session configuration only");
        }

        public synchronized Store session(SessionScope scope) {
            var issued = scopes.get(scope.sessionId());
            if (issued == null || !scope.equals(issued))
                throw new SecurityException("Unknown fixture scope");
            return stores.computeIfAbsent(scope.sessionId(), ignored -> new MemoryStore());
        }

        public synchronized Page<Scope> scopes(Kind kind, @Nullable String cursor, int limit) {
            if (cursor != null) throw new IllegalArgumentException("Unknown cursor");
            return new Page<>(
                    kind == Kind.SESSION ? List.copyOf(scopes.values()) : List.of(), null);
        }

        public synchronized SessionScope currentSession() {
            var invocation = PluginInvocationScope.current();
            var call = ToolCallContextHolder.get();
            @Nullable String owner =
                    invocation == null ? (call == null ? null : call.owner()) : invocation.owner;
            var callSession = call == null ? null : call.sessionId();
            @Nullable String session =
                    invocation == null
                            ? (callSession == null ? null : callSession.toString())
                            : invocation.session;
            if (owner == null || session == null)
                throw new SecurityException("No authenticated fixture invocation");
            var scope =
                    scopes.computeIfAbsent(
                            session,
                            id -> new SessionScope(UUID.randomUUID().toString(), owner, id));
            if (!scope.userId().equals(owner))
                throw new SecurityException("Fixture owner mismatch");
            return scope;
        }

        public UserScope currentUser() {
            throw new UnsupportedOperationException("Fixture supports session configuration only");
        }
    }

    private static final class MemoryStore implements PluginStorage.Store {
        private final Map<String, PluginStorage.Entry> rows = new HashMap<>();

        public synchronized Optional<PluginStorage.Entry> get(String key) {
            return Optional.ofNullable(rows.get(key));
        }

        public synchronized PluginStorage.Page<PluginStorage.Entry> list(
                String prefix, @Nullable String cursor, int limit) {
            if (cursor != null) throw new IllegalArgumentException("Unknown cursor");
            var found = rows.values().stream().filter(row -> row.key().startsWith(prefix)).toList();
            if (found.size() > limit)
                throw new IllegalStateException("Fixture page capacity exceeded");
            return new PluginStorage.Page<>(found, null);
        }

        public synchronized PluginStorage.Entry put(
                String key, @Nullable String revision, PluginStorage.Document document) {
            var old = rows.get(key);
            if (!Objects.equals(old == null ? null : old.revision(), revision))
                throw new PluginStorage.Conflict();
            var entry = new PluginStorage.Entry(key, UUID.randomUUID().toString(), document);
            rows.put(key, entry);
            return entry;
        }

        public synchronized void delete(String key, String revision) {
            var old = rows.get(key);
            if (old == null || !old.revision().equals(revision)) throw new PluginStorage.Conflict();
            rows.remove(key);
        }
    }
}
