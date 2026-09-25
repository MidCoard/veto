package top.focess.veto.integration.plugins.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.JsonValues;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.integration.plugins.PluginHostServices;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.plugin.runtime.ManagedPlugin;
import top.focess.veto.plugin.runtime.PluginJson;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.UserContext;
import top.focess.veto.vault.UserEntity;

/** Scoped CAS records share the host transaction and referential deletion boundary. */
@Component
@NullMarked
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public class ScopedPluginStorage implements PluginStorageFactory {
    private final EntityManager database;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final int maxBytes;

    public ScopedPluginStorage(
            EntityManager database,
            PlatformTransactionManager transactions,
            ObjectMapper mapper,
            @Value("${veto.plugins.storage.max-document-bytes:1048576}") int maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("Storage byte limit must be positive");
        this.database = database;
        this.transactions = new TransactionTemplate(transactions);
        this.mapper = mapper;
        this.maxBytes = maxBytes;
    }

    @Bean
    public PluginHostServices storageHostServices() {
        return new PluginHostServices(Map.of(PluginStorageFactory.class, this));
    }

    @Override
    public PluginStorage bind(ManagedPlugin plugin) {
        return new Bound(plugin);
    }

    @Override
    public String authorizeSession(PluginStorage storage, PluginStorage.SessionScope scope) {
        if (!(storage instanceof Bound bound))
            throw new SecurityException("Unrecognized storage binding");
        return transaction(() -> bound.validate(scope).owner());
    }

    /** Called inside the permanent deletion transaction even when no plugin is loaded. */
    public void deleteSession(String session) {
        database.createQuery("delete from PluginRecord r where r.session.id = :session")
                .setParameter("session", session)
                .executeUpdate();
    }

    public void deleteUser(String username) {
        database.createQuery("delete from PluginRecord r where r.user.username = :owner")
                .setParameter("owner", username)
                .executeUpdate();
    }

    private <T> T transaction(Supplier<T> operation) {
        T result = transactions.execute(status -> operation.get());
        if (result == null)
            throw new IllegalStateException("Storage transaction returned no result");
        return result;
    }

    private record Grant(PluginStorage.Scope scope, String owner) {}

    private final class Bound implements PluginStorage {
        private final ManagedPlugin plugin;
        private final String namespace;
        private final Set<String> selectionIds;
        private final Map<String, Grant> grants = new HashMap<>();

        Bound(ManagedPlugin plugin) {
            this.plugin = plugin;
            namespace = plugin.identity().id();
            var ids = new java.util.HashSet<>(plugin.implementation().historicalIds());
            ids.add(namespace);
            selectionIds = Set.copyOf(ids);
        }

        private void admitted() {
            PluginState state = plugin.state();
            if (state != PluginState.ACTIVE && state != PluginState.STARTING)
                throw new IllegalStateException("Plugin is not active");
        }

        @SuppressWarnings("ConstantValue") // WHY: EntityManager.find returns null for a missing row
        private synchronized Scope issue(String owner, @Nullable String session) {
            admitted();
            UserEntity user = database.find(ToolDocs.nonNullClass(UserEntity.class), owner);
            if (user == null) throw new SecurityException("User scope no longer exists");
            String identity = user.storageIdentity();
            database.flush();
            if (session != null) validateSession(owner, session);
            for (Grant grant : grants.values()) {
                Scope scope = grant.scope();
                if (scope.userId().equals(identity)
                        && (session == null && scope instanceof UserScope
                                || scope instanceof SessionScope value
                                        && value.sessionId().equals(session))) return scope;
            }
            String token = UUID.randomUUID().toString();
            Scope scope =
                    session == null
                            ? new UserScope(token, identity)
                            : new SessionScope(token, identity, session);
            grants.put(token, new Grant(scope, owner));
            return scope;
        }

        @SuppressWarnings("ConstantValue") // WHY: EntityManager.find returns null for a missing row
        private SessionEntity validateSession(String owner, String id) {
            SessionEntity session = database.find(ToolDocs.nonNullClass(SessionEntity.class), id);
            if (session == null || !session.getOwner().equals(owner))
                throw new SecurityException("Session scope no longer exists");
            var bindings = session.getPluginBindings();
            if (bindings == null
                    || bindings.stream().noneMatch(binding -> selectionIds.contains(binding.id())))
                throw new SecurityException("Plugin is not selected for this session");
            return session;
        }

        @SuppressWarnings("ConstantValue") // WHY: EntityManager.find returns null for a missing row
        private synchronized Grant validate(Scope scope) {
            admitted();
            Grant grant = grants.get(scope.token());
            if (grant == null || !grant.scope().equals(scope))
                throw new SecurityException("Unrecognized storage scope");
            UserEntity user = database.find(ToolDocs.nonNullClass(UserEntity.class), grant.owner());
            if (user == null || !user.storageIdentity().equals(scope.userId()))
                throw new SecurityException("Expired storage scope");
            if (scope instanceof SessionScope session)
                validateSession(grant.owner(), session.sessionId());
            return grant;
        }

        @Override
        @SuppressWarnings(
                "resource") // WHY: the invocation scope is owned by its opener, closed elsewhere
        public SessionScope currentSession() {
            var callback = PluginInvocationScope.current();
            if (callback != null)
                return transaction(() -> (SessionScope) issue(callback.owner, callback.session));
            var call = ToolCallContextHolder.get();
            if (call == null || call.owner() == null || call.sessionId() == null)
                throw new SecurityException("No authenticated session invocation");
            String owner = Nullness.requireNonNull(call.owner());
            String session = Nullness.requireNonNull(call.sessionId()).toString();
            return transaction(() -> (SessionScope) issue(owner, session));
        }

        @Override
        public UserScope currentUser() {
            String owner = UserContext.get();
            if (owner == null) throw new SecurityException("No authenticated user invocation");
            return transaction(() -> (UserScope) issue(owner, null));
        }

        @Override
        public Store application() {
            admitted();
            return new BoundStore(null);
        }

        @Override
        public Store user(UserScope scope) {
            transaction(() -> validate(scope));
            return new BoundStore(scope);
        }

        @Override
        public Store session(SessionScope scope) {
            transaction(() -> validate(scope));
            return new BoundStore(scope);
        }

        @Override
        public Page<Scope> scopes(Kind kind, @Nullable String cursor, int limit) {
            if (kind == Kind.APPLICATION)
                throw new IllegalArgumentException("Application scope is directly bound");
            return transaction(
                    () -> {
                        admitted();
                        int pageSize = limit(limit);
                        String after = cursor(namespace + ":" + kind, cursor);
                        if (kind == Kind.SESSION) {
                            var sessions =
                                    database.createQuery(
                                                    "select s from SessionEntity s where s.id >"
                                                            + " :after order by s.id",
                                                    ToolDocs.nonNullClass(SessionEntity.class))
                                            .setParameter("after", after)
                                            .setMaxResults(pageSize + 1)
                                            .getResultList();
                            List<Scope> scopes = new ArrayList<>();
                            for (var session :
                                    sessions.subList(0, Math.min(pageSize, sessions.size()))) {
                                try {
                                    scopes.add(issue(session.getOwner(), session.getId()));
                                } catch (SecurityException ignored) {
                                    /* Only selected, existing sessions are granted. */
                                }
                            }
                            return new Page<>(
                                    scopes,
                                    sessions.size() > pageSize
                                            ? encode(
                                                    namespace + ":" + kind,
                                                    sessions.get(pageSize - 1).getId())
                                            : null);
                        }
                        var rows =
                                database.createQuery(
                                                "select distinct r.scope from PluginRecord r where"
                                                        + " r.plugin = :plugin and r.kind = :kind and"
                                                        + " r.scope > :after order by r.scope",
                                                String.class)
                                        .setParameter("plugin", namespace)
                                        .setParameter("kind", kind.name())
                                        .setParameter("after", after)
                                        .setMaxResults(pageSize + 1)
                                        .getResultList();
                        List<Scope> scopes = new ArrayList<>();
                        int count = Math.min(rows.size(), pageSize);
                        for (String identity : rows.subList(0, count)) {
                            var records =
                                    database.createQuery(
                                                    "select r from PluginRecord r where r.plugin ="
                                                            + " :plugin and r.kind = :kind and r.scope"
                                                            + " = :scope",
                                                    PluginRecord.class)
                                            .setParameter("plugin", namespace)
                                            .setParameter("kind", kind.name())
                                            .setParameter("scope", identity)
                                            .setMaxResults(1)
                                            .getResultList();
                            if (records.isEmpty()) continue;
                            var record = records.getFirst();
                            if (record.user == null) continue;
                            try {
                                scopes.add(issue(record.user.getUsername(), null));
                            } catch (SecurityException ignored) {
                                /* Deleted or no longer selected scopes are not recoverable. */
                            }
                        }
                        return new Page<>(
                                scopes,
                                rows.size() > pageSize
                                        ? encode(namespace + ":" + kind, rows.get(pageSize - 1))
                                        : null);
                    });
        }

        private final class BoundStore implements Store {
            private final @Nullable Scope scope;
            private final String kind;
            private final String identity;
            private final String cursorBinding;

            BoundStore(@Nullable Scope scope) {
                this.scope = scope;
                kind =
                        scope == null
                                ? "APPLICATION"
                                : scope instanceof SessionScope ? "SESSION" : "USER";
                identity =
                        scope == null
                                ? "application"
                                : scope instanceof SessionScope session
                                        ? session.sessionId()
                                        : scope.userId();
                cursorBinding = namespace + ":" + kind + ":" + identity;
            }

            private void check() {
                admitted();
                if (scope != null) validate(scope);
            }

            private List<PluginRecord> find(String key) {
                return database.createQuery(
                                "select r from PluginRecord r where r.plugin = :plugin and r.kind ="
                                        + " :kind and r.scope = :scope and r.key = :key",
                                PluginRecord.class)
                        .setParameter("plugin", namespace)
                        .setParameter("kind", kind)
                        .setParameter("scope", identity)
                        .setParameter("key", key)
                        .getResultList();
            }

            @Override
            public Optional<Entry> get(String key) {
                key(key);
                return transaction(
                        () -> {
                            check();
                            return find(key).stream()
                                    .findFirst()
                                    .map(ScopedPluginStorage.this::entry);
                        });
            }

            @Override
            public Page<Entry> list(String prefix, @Nullable String cursor, int limit) {
                if (prefix.length() > 256)
                    throw new IllegalArgumentException("Prefix exceeds key limit");
                return transaction(
                        () -> {
                            check();
                            int pageSize = limit(limit);
                            String after = cursor(cursorBinding + ":" + prefix, cursor);
                            var rows =
                                    database.createQuery(
                                                    "select r from PluginRecord r where r.plugin ="
                                                            + " :plugin and r.kind = :kind and r.scope"
                                                            + " = :scope and r.key > :after and"
                                                            + " substring(r.key, 1, :length) = :prefix"
                                                            + " order by r.key",
                                                    PluginRecord.class)
                                            .setParameter("plugin", namespace)
                                            .setParameter("kind", kind)
                                            .setParameter("scope", identity)
                                            .setParameter("after", after)
                                            .setParameter("length", prefix.length())
                                            .setParameter("prefix", prefix)
                                            .setMaxResults(pageSize + 1)
                                            .getResultList();
                            return new Page<>(
                                    rows.subList(0, Math.min(pageSize, rows.size())).stream()
                                            .map(ScopedPluginStorage.this::entry)
                                            .toList(),
                                    rows.size() > pageSize
                                            ? encode(
                                                    cursorBinding + ":" + prefix,
                                                    rows.get(pageSize - 1).key)
                                            : null);
                        });
            }

            @Override
            public Entry put(String key, @Nullable String expected, Document document) {
                ToolCallContextHolder.requireEffects();
                key(key);
                String payload = PluginJson.toNode(document.value()).toString();
                if (payload.getBytes(StandardCharsets.UTF_8).length > maxBytes)
                    throw new IllegalArgumentException("Plugin document exceeds byte limit");
                try {
                    return transaction(
                            () -> {
                                check();
                                String revision = UUID.randomUUID().toString();
                                if (expected != null) {
                                    var affected = find(key);
                                    int updated =
                                            database.createQuery(
                                                            "update PluginRecord r set r.payload ="
                                                                    + " :payload, r.schemaVersion ="
                                                                    + " :schema, r.revision = :revision"
                                                                    + " where r.plugin = :plugin and"
                                                                    + " r.kind = :kind and r.scope ="
                                                                    + " :scope and r.key = :key and"
                                                                    + " r.revision = :expected")
                                                    .setParameter("payload", payload)
                                                    .setParameter(
                                                            "schema", document.schemaVersion())
                                                    .setParameter("revision", revision)
                                                    .setParameter("plugin", namespace)
                                                    .setParameter("kind", kind)
                                                    .setParameter("scope", identity)
                                                    .setParameter("key", key)
                                                    .setParameter("expected", expected)
                                                    .executeUpdate();
                                    if (updated != 1) throw new Conflict();
                                    for (var row : affected) database.refresh(row);
                                } else {
                                    if (!find(key).isEmpty()) throw new Conflict();
                                    var row = new PluginRecord();
                                    row.id = UUID.randomUUID().toString();
                                    row.plugin = namespace;
                                    row.kind = kind;
                                    row.scope = identity;
                                    row.key = key;
                                    row.revision = revision;
                                    row.schemaVersion = document.schemaVersion();
                                    row.payload = payload;
                                    if (scope != null) {
                                        Grant grant = validate(scope);
                                        row.user =
                                                database.find(
                                                        ToolDocs.nonNullClass(UserEntity.class),
                                                        grant.owner());
                                        if (scope instanceof SessionScope session)
                                            row.session =
                                                    validateSession(
                                                            grant.owner(), session.sessionId());
                                    }
                                    database.persist(row);
                                    database.flush();
                                }
                                return new Entry(key, revision, document);
                            });
                } catch (DataIntegrityViolationException | PersistenceException error) {
                    if (expected == null && uniqueViolation(error)) throw new Conflict();
                    throw error;
                }
            }

            @Override
            public void delete(String key, String expected) {
                ToolCallContextHolder.requireEffects();
                key(key);
                transaction(
                        () -> {
                            check();
                            var affected = find(key);
                            int deleted =
                                    database.createQuery(
                                                    "delete from PluginRecord r where r.plugin ="
                                                            + " :plugin and r.kind = :kind and r.scope"
                                                            + " = :scope and r.key = :key and"
                                                            + " r.revision = :revision")
                                            .setParameter("plugin", namespace)
                                            .setParameter("kind", kind)
                                            .setParameter("scope", identity)
                                            .setParameter("key", key)
                                            .setParameter("revision", expected)
                                            .executeUpdate();
                            if (deleted != 1) throw new Conflict();
                            for (var row : affected) database.detach(row);
                            return true;
                        });
            }
        }
    }

    private PluginStorage.Entry entry(PluginRecord row) {
        try {
            return new PluginStorage.Entry(
                    row.key,
                    row.revision,
                    new PluginStorage.Document(
                            row.schemaVersion,
                            JsonValues.from(
                                    Nullness.requireNonNull(mapper.readTree(row.payload)))));
        } catch (Exception error) {
            throw new IllegalStateException("Invalid plugin document", error);
        }
    }

    private static void key(String value) {
        if (value.isEmpty() || value.length() > 256)
            throw new IllegalArgumentException("Key must contain 1 to 256 characters");
    }

    private static boolean uniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && ("23505".equals(sql.getSQLState()) || sql.getErrorCode() == 1062))
                return true;
        }
        return false;
    }

    private static int limit(int value) {
        if (value < 0 || value > 200)
            throw new IllegalArgumentException("Page limit must be 1 to 200");
        return value == 0 ? 50 : value;
    }

    private static String encode(String binding, String key) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((binding + "\u0000" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static String cursor(String binding, @Nullable String cursor) {
        if (cursor == null) return "";
        String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        String prefix = binding + "\u0000";
        if (!decoded.startsWith(prefix))
            throw new IllegalArgumentException("Cursor belongs to another store or query");
        return decoded.substring(prefix.length());
    }
}
