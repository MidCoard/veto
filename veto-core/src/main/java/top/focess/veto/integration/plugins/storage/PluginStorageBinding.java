package top.focess.veto.integration.plugins.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.transaction.support.TransactionTemplate;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.plugin.PluginScope;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.plugin.runtime.ManagedPlugin;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.CurrentUser;
import top.focess.veto.vault.UserEntity;

/** One plugin activation and its issued grants; grant state is synchronized on this binding. */
final class PluginStorageBinding implements PluginStorage {
    private final @NonNull EntityManager database;
    private final @NonNull TransactionTemplate transactions;
    private final @NonNull ObjectMapper mapper;
    private final int maxBytes;
    private final @NonNull ManagedPlugin plugin;
    final @NonNull String namespace;
    private final @NonNull Set<@NonNull String> selectionIds;
    private final @NonNull Map<@NonNull String, @NonNull IssuedStorageGrant> grants =
            new HashMap<>();

    PluginStorageBinding(
            @NonNull ManagedPlugin plugin,
            @NonNull EntityManager database,
            @NonNull TransactionTemplate transactions,
            @NonNull ObjectMapper mapper,
            int maxBytes) {
        this.database = database;
        this.transactions = transactions;
        this.mapper = mapper;
        this.maxBytes = maxBytes;
        this.plugin = plugin;
        namespace = plugin.identity().id();
        var ids = new HashSet<>(plugin.historicalIds());
        ids.add(namespace);
        selectionIds = Set.copyOf(ids);
    }

    void admitted() {
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
        for (IssuedStorageGrant issued : grants.values()) {
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
        grants.put(token, new IssuedStorageGrant(grant, userId));
        return grant;
    }

    PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> issueUser(@NonNull UUID userId) {
        var issued = issue(userId, null);
        if (!(issued.scope() instanceof Scope.UserScope user))
            throw new IllegalStateException("User scope has another identity type");
        return new PluginStorage.Grant<>(issued.token(), user);
    }

    PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> issueSession(
            @NonNull UUID userId, @NonNull String session) {
        var issued = issue(userId, session);
        if (!(issued.scope() instanceof Scope.SessionScope identity))
            throw new IllegalStateException("Session scope has another identity type");
        return new PluginStorage.Grant<>(issued.token(), identity);
    }

    @SuppressWarnings("ConstantValue") // WHY: EntityManager.find returns null for a missing row
    @NonNull SessionEntity validateSession(@NonNull UUID userId, @NonNull String id) {
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
    synchronized @NonNull IssuedStorageGrant validate(PluginStorage.@NonNull Grant<?> grant) {
        admitted();
        IssuedStorageGrant issued = grants.get(grant.token());
        if (issued == null || !issued.grant().equals(grant))
            throw new SecurityException("Unrecognized storage scope");
        UserEntity user = database.find(UserEntity.class, issued.userId());
        if (user == null || !user.getUserId().equals(grant.scope().userId()))
            throw new SecurityException("Expired storage scope");
        if (grant.scope() instanceof Scope.SessionScope session)
            validateSession(issued.userId(), session.session());
        return issued;
    }

    @NonNull IssuedStorageGrant validate(
            PluginStorage.@NonNull Grant<?> grant, @NonNull PluginScope kind) {
        if (kind == PluginScope.USER && !(grant.scope() instanceof Scope.UserScope)
                || kind == PluginScope.SESSION && !(grant.scope() instanceof Scope.SessionScope))
            throw new SecurityException("Storage grant kind does not match the operation");
        return validate(grant);
    }

    @Override
    @SuppressWarnings(
            "resource") // WHY: the invocation context is owned by its opener, closed elsewhere
    public PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> currentSession() {
        var callback = PluginInvocationContext.current();
        if (callback != null)
            return ScopedPluginStorage.transaction(
                    transactions, () -> issueSession(callback.userId, callback.session));
        var call = ToolCallContextHolder.get();
        if (call == null || call.sessionId() == null)
            throw new SecurityException("No authenticated session invocation");
        UUID userId = call.userId();
        String session = Nullness.requireNonNull(call.sessionId()).toString();
        return ScopedPluginStorage.transaction(transactions, () -> issueSession(userId, session));
    }

    @Override
    public PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> currentUser() {
        UUID userId = CurrentUser.id();
        if (userId == null) throw new SecurityException("No authenticated user invocation");
        return ScopedPluginStorage.transaction(transactions, () -> issueUser(userId));
    }

    @Override
    public @NonNull Store application() {
        admitted();
        return new PluginRecordStore(this, database, transactions, mapper, maxBytes, null);
    }

    @Override
    public @NonNull Store user(PluginStorage.@NonNull Grant<Scope.@NonNull UserScope> grant) {
        ScopedPluginStorage.transaction(transactions, () -> validate(grant, PluginScope.USER));
        return new PluginRecordStore(this, database, transactions, mapper, maxBytes, grant);
    }

    @Override
    public @NonNull Store session(PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> grant) {
        ScopedPluginStorage.transaction(transactions, () -> validate(grant, PluginScope.SESSION));
        return new PluginRecordStore(this, database, transactions, mapper, maxBytes, grant);
    }

    @Override
    public @NonNull Page<PluginStorage.@NonNull Grant<?>> scopes(
            @NonNull PluginScope kind, String cursor, int limit) {
        if (kind == PluginScope.APPLICATION || kind == PluginScope.AGENT)
            throw new IllegalArgumentException("Only user and session scopes can be listed");
        return ScopedPluginStorage.transaction(
                transactions,
                () -> {
                    admitted();
                    int pageSize = PluginRecordStore.limit(limit);
                    String after = PluginRecordStore.cursor(namespace + ":" + kind, cursor);
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
                                        ? PluginRecordStore.encode(
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
                                    ? PluginRecordStore.encode(
                                            namespace + ":" + kind, rows.get(pageSize - 1))
                                    : null);
                });
    }

    /** One activation-issued scope grant and its canonical user identity. */
    record IssuedStorageGrant(PluginStorage.@NonNull Grant<?> grant, @NonNull UUID userId) {}
}
