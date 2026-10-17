package top.focess.veto.integration.plugins.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.JsonValues;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.api.plugin.storage.PluginStorage.Conflict;
import top.focess.veto.api.plugin.storage.PluginStorage.Document;
import top.focess.veto.api.plugin.storage.PluginStorage.Entry;
import top.focess.veto.api.plugin.storage.PluginStorage.Page;
import top.focess.veto.integration.plugins.storage.PluginStorageBinding.IssuedStorageGrant;
import top.focess.veto.plugin.runtime.PluginJson;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.UserEntity;

/** Transactional CAS records for one plugin scope, authorized through its issuing binding. */
final class PluginRecordStore implements PluginStorage.Store {
    private final @NonNull EntityManager database;
    private final @NonNull TransactionTemplate transactions;
    private final @NonNull ObjectMapper mapper;
    private final int maxBytes;
    private final @NonNull PluginStorageBinding binding;
    private final PluginStorage.Grant<?> grant;
    private final @NonNull String kind;
    private final @NonNull String identity;
    private final @NonNull String cursorBinding;

    PluginRecordStore(
            @NonNull PluginStorageBinding binding,
            @NonNull EntityManager database,
            @NonNull TransactionTemplate transactions,
            @NonNull ObjectMapper mapper,
            int maxBytes,
            PluginStorage.Grant<?> grant) {
        this.binding = binding;
        this.database = database;
        this.transactions = transactions;
        this.mapper = mapper;
        this.maxBytes = maxBytes;
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
        cursorBinding = binding.namespace + ":" + kind + ":" + identity;
    }

    private void check() {
        binding.admitted();
        if (grant != null) binding.validate(grant);
    }

    private @NonNull List<@NonNull PluginRecord> find(@NonNull String key) {
        return database.createQuery(
                        "select r from PluginRecord r where r.plugin = :plugin and r.kind ="
                                + " :kind and r.scope = :scope and r.key = :key",
                        PluginRecord.class)
                .setParameter("plugin", binding.namespace)
                .setParameter("kind", kind)
                .setParameter("scope", identity)
                .setParameter("key", key)
                .getResultList();
    }

    @Override
    public @NonNull Optional<@NonNull Entry> get(@NonNull String key) {
        key(key);
        return ScopedPluginStorage.transaction(
                transactions,
                () -> {
                    check();
                    return find(key).stream().findFirst().map(this::entry);
                });
    }

    @Override
    public @NonNull Page<@NonNull Entry> list(@NonNull String prefix, String cursor, int limit) {
        if (prefix.length() > 256) throw new IllegalArgumentException("Prefix exceeds key limit");
        return ScopedPluginStorage.transaction(
                transactions,
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
                                    .setParameter("plugin", binding.namespace)
                                    .setParameter("kind", kind)
                                    .setParameter("scope", identity)
                                    .setParameter("after", after)
                                    .setParameter("length", prefix.length())
                                    .setParameter("prefix", prefix)
                                    .setMaxResults(pageSize + 1)
                                    .getResultList();
                    return new Page<>(
                            rows.subList(0, Math.min(pageSize, rows.size())).stream()
                                    .map(this::entry)
                                    .toList(),
                            rows.size() > pageSize
                                    ? encode(
                                            cursorBinding + ":" + prefix,
                                            rows.get(pageSize - 1).key)
                                    : null);
                });
    }

    @Override
    public @NonNull Entry put(@NonNull String key, String expected, @NonNull Document document) {
        ToolCallContextHolder.requireEffects();
        key(key);
        String payload = PluginJson.toNode(document.value()).toString();
        if (payload.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new IllegalArgumentException("Plugin document exceeds byte limit");
        try {
            return ScopedPluginStorage.transaction(
                    transactions,
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
                                            .setParameter("schema", document.schemaVersion())
                                            .setParameter("revision", revision)
                                            .setParameter("plugin", binding.namespace)
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
                            row.plugin = binding.namespace;
                            row.kind = kind;
                            row.scope = identity;
                            row.key = key;
                            row.revision = revision;
                            row.schemaVersion = document.schemaVersion();
                            row.payload = payload;
                            if (grant != null) {
                                IssuedStorageGrant issued = binding.validate(grant);
                                row.user = database.find(UserEntity.class, issued.userId());
                                if (grant.scope() instanceof Scope.SessionScope session)
                                    row.session =
                                            binding.validateSession(
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
        ScopedPluginStorage.transaction(
                transactions,
                () -> {
                    check();
                    var affected = find(key);
                    int deleted =
                            database.createQuery(
                                            "delete from PluginRecord r where r.plugin ="
                                                    + " :plugin and r.kind = :kind and r.scope"
                                                    + " = :scope and r.key = :key and"
                                                    + " r.revision = :revision")
                                    .setParameter("plugin", binding.namespace)
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

    static int limit(int value) {
        if (value < 0 || value > 200)
            throw new IllegalArgumentException("Page limit must be 1 to 200");
        return value == 0 ? 50 : value;
    }

    static @NonNull String encode(@NonNull String binding, @NonNull String key) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((binding + "\u0000" + key).getBytes(StandardCharsets.UTF_8));
    }

    static @NonNull String cursor(@NonNull String binding, String cursor) {
        if (cursor == null) return "";
        String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        String prefix = binding + "\u0000";
        if (!decoded.startsWith(prefix))
            throw new IllegalArgumentException("Cursor belongs to another store or query");
        return decoded.substring(prefix.length());
    }
}
