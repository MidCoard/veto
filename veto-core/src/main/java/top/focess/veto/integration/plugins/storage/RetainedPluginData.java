package top.focess.veto.integration.plugins.storage;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.controller.RequestAuthorization;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.plugin.runtime.PluginLifecycle;
import top.focess.veto.plugin.runtime.ScriptPlugin;

/** Host-owned, read-only view of opaque plugin records, independent of plugin decoders. */
@Service
public class RetainedPluginData {
    /** Whether the plugin identified by a stored record is currently installed and usable. */
    public enum Presence {
        ACTIVE,
        INACTIVE,
        ABSENT
    }

    /** The host preserves the payload but does not claim to understand its plugin schema. */
    public enum Interpretation {
        OPAQUE
    }

    /** Metadata visible without exporting the document body. */
    public record Metadata(
            @NonNull String id,
            @NonNull String pluginId,
            @Nullable String installedPluginId,
            PluginStorage.@NonNull Kind kind,
            @NonNull String scopeId,
            @NonNull String key,
            @NonNull String revision,
            int schemaVersion,
            int payloadBytes,
            @NonNull Presence presence,
            @NonNull Interpretation interpretation) {}

    /** One bounded page ordered by durable record identity. */
    public record Page(@NonNull List<@NonNull Metadata> entries, @Nullable String nextCursor) {
        public Page {
            entries = List.copyOf(entries);
        }
    }

    /** Explicit export envelope; payload is the exact stored JSON text, never host-decoded. */
    public record Export(@NonNull Metadata metadata, @NonNull String payload) {}

    private final @NonNull EntityManager database;
    private final @NonNull PluginManager plugins;
    private final @NonNull RequestAuthorization authorization;

    /** Creates the host-managed read-only inventory. */
    public RetainedPluginData(
            @NonNull EntityManager database,
            @NonNull PluginManager plugins,
            @NonNull RequestAuthorization authorization) {
        this.database = database;
        this.plugins = plugins;
        this.authorization = authorization;
    }

    /** Lists only metadata from one scope kind; application records require an administrator. */
    @Transactional(readOnly = true)
    public @NonNull Page list(
            PluginStorage.@NonNull Kind kind,
            @Nullable String pluginId,
            @Nullable String after,
            int limit) {
        String owner = authorization.requireUser();
        if (kind == PluginStorage.Kind.APPLICATION) authorization.requireAdmin();
        if (limit < 1 || limit > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit must be 1 to 100");
        String query =
                "select r from PluginRecord r where r.kind = :kind and r.id > :after"
                        + ownerClause(kind)
                        + (pluginId == null ? "" : " and r.plugin = :plugin")
                        + " order by r.id";
        var selection =
                database.createQuery(query, PluginRecord.class)
                        .setParameter("kind", kind.name())
                        .setParameter("after", after == null ? "" : after)
                        .setMaxResults(limit + 1);
        if (kind != PluginStorage.Kind.APPLICATION) selection.setParameter("owner", owner);
        if (pluginId != null) selection.setParameter("plugin", pluginId);
        List<PluginRecord> rows = selection.getResultList();
        int count = Math.min(limit, rows.size());
        List<Metadata> entries = rows.subList(0, count).stream().map(this::metadata).toList();
        return new Page(entries, rows.size() > limit ? rows.get(count - 1).id : null);
    }

    /**
     * Exports exactly one authorized stored document; missing and unauthorized IDs both yield 404.
     */
    @Transactional(readOnly = true)
    public @NonNull Export export(@NonNull String id) {
        String owner = authorization.requireUser();
        PluginRecord row = database.find(PluginRecord.class, id);
        if (row == null) throw notFound();
        PluginStorage.Kind kind;
        try {
            kind = PluginStorage.Kind.valueOf(row.kind);
        } catch (IllegalArgumentException invalid) {
            throw notFound();
        }
        if (kind == PluginStorage.Kind.APPLICATION) {
            try {
                authorization.requireAdmin();
            } catch (ResponseStatusException denied) {
                throw notFound();
            }
        } else if (row.user == null
                || !owner.equals(row.user.getUsername())
                || (kind == PluginStorage.Kind.SESSION
                        && (row.session == null || !owner.equals(row.session.getOwner())))) {
            throw notFound();
        }
        return new Export(metadata(row), row.payload);
    }

    private static @NonNull String ownerClause(PluginStorage.@NonNull Kind kind) {
        return switch (kind) {
            case APPLICATION -> "";
            case USER -> " and r.user.username = :owner";
            case SESSION -> " and r.user.username = :owner and r.session.owner = :owner";
        };
    }

    // Checker treats a nested enum valueOf result as nullable under the package default.
    @SuppressWarnings("ConstantValue")
    private @NonNull Metadata metadata(@NonNull PluginRecord row) {
        PluginStorage.Kind kind = PluginStorage.Kind.valueOf(row.kind);
        if (kind == null) throw new IllegalStateException("Unknown plugin record kind");
        PluginLifecycle installed;
        try {
            installed = plugins.plugin(row.plugin);
        } catch (IllegalArgumentException missing) {
            installed = null;
        }
        Presence presence =
                installed == null
                        ? plugins.isDeclined(row.plugin) || plugins.isDisabled(row.plugin)
                                ? Presence.INACTIVE
                                : Presence.ABSENT
                        : installed.state() == PluginState.ACTIVE
                                        && (!(installed.implementation()
                                                        instanceof ScriptPlugin script)
                                                || script.active())
                                ? Presence.ACTIVE
                                : Presence.INACTIVE;
        return new Metadata(
                row.id,
                row.plugin,
                installed == null ? null : installed.identity().id(),
                kind,
                row.scope,
                row.key,
                row.revision,
                row.schemaVersion,
                row.payload.getBytes(StandardCharsets.UTF_8).length,
                presence,
                Interpretation.OPAQUE);
    }

    private static @NonNull ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Plugin record not found");
    }
}
