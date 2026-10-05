package top.focess.veto.integration.plugins.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.plugin.PluginScope;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.Scope;
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
public class ScopedPluginStorage implements PluginStorageFactory {
    private final @NonNull EntityManager database;
    private final @NonNull TransactionTemplate transactions;
    private final @NonNull ObjectMapper mapper;
    private final int maxBytes;

    /** Creates the storage engine; {@code maxBytes} caps one plugin document, must be positive. */
    public ScopedPluginStorage(
            @NonNull EntityManager database,
            @NonNull PlatformTransactionManager transactions,
            @NonNull ObjectMapper mapper,
            @Value("${veto.plugins.storage.max-document-bytes:1048576}") int maxBytes) {
        if (maxBytes < 1) throw new IllegalArgumentException("Storage byte limit must be positive");
        this.database = database;
        this.transactions = new TransactionTemplate(transactions);
        this.mapper = mapper;
        this.maxBytes = maxBytes;
    }

    /** Exposes this factory as a host service so the manager can bind per-plugin storage. */
    @Bean
    public @NonNull PluginHostServices storageHostServices() {
        return new PluginHostServices(Map.of(PluginStorageFactory.class, this));
    }

    @Override
    public @NonNull PluginStorage bind(@NonNull ManagedPlugin plugin) {
        return new Bound(plugin);
    }

    @Override
    public @NonNull UUID authorizeSession(
            @NonNull PluginStorage storage,
            PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> grant) {
        if (!(storage instanceof Bound bound))
            throw new SecurityException("Unrecognized storage binding");
        return transaction(() -> bound.validate(grant, PluginScope.SESSION).userId());
    }

    @Override
    public @NonNull UUID authorizeUser(
            @NonNull PluginStorage storage,
            PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> grant) {
        if (!(storage instanceof Bound bound))
            throw new SecurityException("Unrecognized storage binding");
        return transaction(() -> bound.validate(grant, PluginScope.USER).userId());
    }

    @Override
    public PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> transferUser(
            @NonNull PluginStorage caller,
            PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> grant,
            @NonNull PluginStorage provider) {
        if (!(caller instanceof Bound callerBound) || !(provider instanceof Bound providerBound))
            throw new SecurityException("Unrecognized storage binding");
        return transaction(
                () ->
                        providerBound.issueUser(
                                callerBound.validate(grant, PluginScope.USER).userId()));
    }

    @Override
    public PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> transferSession(
            @NonNull PluginStorage caller,
            PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> grant,
            @NonNull PluginStorage provider) {
        if (!(caller instanceof Bound callerBound) || !(provider instanceof Bound providerBound))
            throw new SecurityException("Unrecognized storage binding");
        return transaction(
                () ->
                        providerBound.issueSession(
                                callerBound.validate(grant, PluginScope.SESSION).userId(),
                                grant.scope().session()));
    }

    /** Called inside the permanent deletion transaction even when no plugin is loaded. */
    public void deleteSession(@NonNull String session) {
        database.createQuery("delete from PluginRecord r where r.session.id = :session")
                .setParameter("session", session)
                .executeUpdate();
    }

    /** Called inside the permanent deletion transaction even when no plugin is loaded. */
    public void deleteUser(@NonNull UUID userId) {
        database.createQuery("delete from PluginRecord r where r.user.userId = :userId")
                .setParameter("userId", userId)
                .executeUpdate();
    }

    private <T> @NonNull T transaction(@NonNull Supplier<@NonNull T> operation) {
        T result = transactions.execute(status -> operation.get());
        if (result == null)
            throw new IllegalStateException("Storage transaction returned no result");
        return result;
    }

    private record IssuedGrant(PluginStorage.@NonNull Grant<?> grant, @NonNull UUID userId) {}

    private final class Bound implements PluginStorage {
        private final @NonNull ManagedPlugin plugin;
        private final @NonNull String namespace;
        private final @NonNull Set<@NonNull String> selectionIds;
        private final @NonNull Map<@NonNull String, @NonNull IssuedGrant> grants = new HashMap<>();

        Bound(@NonNull ManagedPlugin plugin) {
            this.plugin = plugin;
            namespace = plugin.identity().id();
            var ids = new HashSet<>(plugin.historicalIds());
            ids.add(namespace);
            selectionIds = Set.copyOf(ids);
        }

        private void admitted() {
            PluginState state = plugin.state();
            if (state != PluginState.ACTIVE && state != PluginState.STARTING)
                throw new IllegalStateException("Plugin is not active");
        }

        @SuppressWarnings("ConstantValue") // WHY: EntityManager.find returns null for a missing row
        private synchronized PluginStorage.@NonNull Grant<?> issue(
                @NonNull UUID userId, String session) {
            admitted();
            UserEntity user = database.find(UserEntity.class, userId);
            if (user == null) throw new SecurityException("User scope no longer exists");
            UUID identity = user.getUserId();
            database.flush();
            if (session != null) validateSession(userId, session);
            for (IssuedGrant issued : grants.values()) {
                var grant = issued.grant();
                if (identity.equals(grant.scope().userId())
                        && (session == null && grant.scope() instanceof Scope.UserScope
                                || grant.scope() instanceof Scope.SessionScope value
                                        && value.session().equals(session))) return grant;
            }
            String token = UUID.randomUUID().toString();
            PluginStorage.Grant<?> grant =
                    session == null
                            ? new PluginStorage.Grant<>(token, new Scope.UserScope(identity))
                            : new PluginStorage.Grant<>(
                                    token, new Scope.SessionScope(identity, session));
            grants.put(token, new IssuedGrant(grant, userId));
            return grant;
        }

        private PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> issueUser(
                @NonNull UUID userId) {
            var issued = issue(userId, null);
            if (!(issued.scope() instanceof Scope.UserScope user))
                throw new IllegalStateException("User scope has another identity type");
            return new PluginStorage.Grant<>(issued.token(), user);
        }

        private PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> issueSession(
                @NonNull UUID userId, @NonNull String session) {
            var issued = issue(userId, session);
            if (!(issued.scope() instanceof Scope.SessionScope identity))
                throw new IllegalStateException("Session scope has another identity type");
            return new PluginStorage.Grant<>(issued.token(), identity);
        }

        @SuppressWarnings("ConstantValue") // WHY: EntityManager.find returns null for a missing row
        private @NonNull SessionEntity validateSession(@NonNull UUID userId, @NonNull String id) {
            SessionEntity session = database.find(SessionEntity.class, id);
            if (session == null || !session.getUserId().equals(userId))
                throw new SecurityException("Session scope no longer exists");
            var bindings = session.getPluginBindings();
            String revision = plugin.binding().revision();
            if (bindings == null
                    || bindings.stream()
                            .noneMatch(
                                    binding ->
                                            selectionIds.contains(binding.id())
                                                    && binding.version()
                                                            .equals(plugin.identity().version())
                                                    && binding.revision().equals(revision)))
                throw new SecurityException("Plugin is not selected for this session");
            return session;
        }

        @SuppressWarnings("ConstantValue") // WHY: EntityManager.find returns null for a missing row
        private synchronized @NonNull IssuedGrant validate(PluginStorage.@NonNull Grant<?> grant) {
            admitted();
            IssuedGrant issued = grants.get(grant.token());
            if (issued == null || !issued.grant().equals(grant))
                throw new SecurityException("Unrecognized storage scope");
            UserEntity user = database.find(UserEntity.class, issued.userId());
            if (user == null || !user.getUserId().equals(grant.scope().userId()))
                throw new SecurityException("Expired storage scope");
            if (grant.scope() instanceof Scope.SessionScope session)
                validateSession(issued.userId(), session.session());
            return issued;
        }

        private @NonNull IssuedGrant validate(
                PluginStorage.@NonNull Grant<?> grant, @NonNull PluginScope kind) {
            if (kind == PluginScope.USER && !(grant.scope() instanceof Scope.UserScope)
                    || kind == PluginScope.SESSION
                            && !(grant.scope() instanceof Scope.SessionScope))
                throw new SecurityException("Storage grant kind does not match the operation");
            return validate(grant);
        }

        @Override
        @SuppressWarnings(
                "resource") // WHY: the invocation context is owned by its opener, closed elsewhere
        public PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> currentSession() {
            var callback = PluginInvocationContext.current();
            if (callback != null)
                return transaction(() -> issueSession(callback.userId, callback.session));
            var call = ToolCallContextHolder.get();
            if (call == null || call.sessionId() == null)
                throw new SecurityException("No authenticated session invocation");
            UUID userId = call.userId();
            String session = Nullness.requireNonNull(call.sessionId()).toString();
            return transaction(() -> issueSession(userId, session));
        }

        @Override
        public PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> currentUser() {
            UUID userId = UserContext.get();
            if (userId == null) throw new SecurityException("No authenticated user invocation");
            return transaction(() -> issueUser(userId));
        }

        @Override
        public @NonNull Store application() {
            admitted();
            return new BoundStore(null);
        }

        @Override
        public @NonNull Store user(PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> grant) {
            transaction(() -> validate(grant, PluginScope.USER));
            return new BoundStore(grant);
        }

        @Override
        public @NonNull Store session(
                PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> grant) {
            transaction(() -> validate(grant, PluginScope.SESSION));
            return new BoundStore(grant);
        }

        @Override
        public @NonNull Page<PluginStorage.@NonNull Grant<?>> scopes(
                @NonNull PluginScope kind, String cursor, int limit) {
            if (kind == PluginScope.APPLICATION || kind == PluginScope.AGENT)
                throw new IllegalArgumentException("Only user and session scopes can be listed");
            return transaction(
                    () -> {
                        admitted();
                        int pageSize = limit(limit);
                        String after = cursor(namespace + ":" + kind, cursor);
                        if (kind == PluginScope.SESSION) {
                            var sessions =
                                    database.createQuery(
                                                    "select s from SessionEntity s where s.id >"
                                                            + " :after order by s.id",
                                                    SessionEntity.class)
                                            .setParameter("after", after)
                                            .setMaxResults(pageSize + 1)
                                            .getResultList();
                            List<PluginStorage.Grant<?>> scopes = new ArrayList<>();
                            for (var session :
                                    sessions.subList(0, Math.min(pageSize, sessions.size()))) {
                                try {
                                    scopes.add(issue(session.getUserId(), session.getId()));
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
                        List<PluginStorage.Grant<?>> scopes = new ArrayList<>();
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
                                scopes.add(issue(record.user.getUserId(), null));
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
            private final PluginStorage.Grant<?> grant;
            private final @NonNull String kind;
            private final @NonNull String identity;
            private final @NonNull String cursorBinding;

            BoundStore(PluginStorage.Grant<?> grant) {
                this.grant = grant;
                if (grant == null) {
                    kind = "APPLICATION";
                    identity = "application";
                } else if (grant.scope() instanceof Scope.SessionScope session) {
                    kind = "SESSION";
                    identity = session.session();
                } else if (grant.scope() instanceof Scope.UserScope user) {
                    kind = "USER";
                    identity = user.userId().toString();
                } else {
                    throw new SecurityException("Unsupported storage scope");
                }
                cursorBinding = namespace + ":" + kind + ":" + identity;
            }

            private void check() {
                admitted();
                if (grant != null) validate(grant);
            }

            private @NonNull List<@NonNull PluginRecord> find(@NonNull String key) {
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
            public @NonNull Optional<@NonNull Entry> get(@NonNull String key) {
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
            public @NonNull Page<@NonNull Entry> list(
                    @NonNull String prefix, String cursor, int limit) {
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
            public @NonNull Entry put(
                    @NonNull String key, String expected, @NonNull Document document) {
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
                                    if (grant != null) {
                                        IssuedGrant issued = validate(grant);
                                        row.user = database.find(UserEntity.class, issued.userId());
                                        if (grant.scope() instanceof Scope.SessionScope session)
                                            row.session =
                                                    validateSession(
                                                            issued.userId(), session.session());
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
            public void delete(@NonNull String key, @NonNull String expected) {
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

    private PluginStorage.@NonNull Entry entry(@NonNull PluginRecord row) {
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

    private static void key(@NonNull String value) {
        if (value.isEmpty() || value.length() > 256)
            throw new IllegalArgumentException("Key must contain 1 to 256 characters");
    }

    private static boolean uniqueViolation(@NonNull Throwable error) {
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

    private static @NonNull String encode(@NonNull String binding, @NonNull String key) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((binding + "\u0000" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static @NonNull String cursor(@NonNull String binding, String cursor) {
        if (cursor == null) return "";
        String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        String prefix = binding + "\u0000";
        if (!decoded.startsWith(prefix))
            throw new IllegalArgumentException("Cursor belongs to another store or query");
        return decoded.substring(prefix.length());
    }
}
