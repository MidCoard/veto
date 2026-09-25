package top.focess.veto.distribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.builtin.BuiltinPlugin;
import top.focess.veto.builtin.monitor.MonitorEntity;
import top.focess.veto.builtin.monitor.MonitorRecord;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.model.SessionRepository;

/** Distribution upgrade only. The legacy table is a read-only backup, never a core schema. */
@Component
@Order(100)
@NullMarked
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public final class LegacyMonitorImport implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyMonitorImport.class);
    private final DataSource database;
    private final PluginManager plugins;
    private final SessionRepository sessions;
    private final SessionPlugins selection;
    private final ObjectMapper mapper;

    /**
     * Creates the runner with its database, plugin, session, selection, and mapper collaborators.
     */
    public LegacyMonitorImport(
            DataSource database,
            PluginManager plugins,
            SessionRepository sessions,
            SessionPlugins selection,
            ObjectMapper mapper) {
        this.database = database;
        this.plugins = plugins;
        this.sessions = sessions;
        this.selection = selection;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        BuiltinPlugin builtin = null;
        for (var plugin : plugins.plugins())
            if (plugin.implementation() instanceof BuiltinPlugin value) builtin = value;
        if (builtin == null) return;
        int failures = 0;
        try (var connection = database.getConnection()) {
            boolean exists = false;
            try (var tables =
                    connection.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
                while (tables.next())
                    if ("agent_monitors".equalsIgnoreCase(tables.getString("TABLE_NAME")))
                        exists = true;
            }
            if (!exists) return;
            try (var query =
                            connection.prepareStatement(
                                    "SELECT id, payload FROM agent_monitors ORDER BY id");
                    var rows = query.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString(1), payload = rows.getString(2);
                    try {
                        if (id == null || payload == null)
                            throw new IllegalArgumentException("Null legacy monitor row");
                        MonitorRecord record =
                                mapper.readValue(
                                        payload, ToolDocs.nonNullClass(MonitorRecord.class));
                        if (!record.id().equals(id))
                            throw new IllegalArgumentException("Invalid legacy identity");
                        var session = sessions.findById(record.sessionId());
                        if (session.isEmpty() || !session.get().getOwner().equals(record.owner())) {
                            log.warn(
                                    "Legacy monitor {} skipped: its owning session no longer exists",
                                    id);
                            continue;
                        }
                        if (!selection.includes(record.sessionId(), builtin.identity().id())) {
                            log.warn(
                                    "Legacy monitor {} retained in backup: builtin is not selected",
                                    id);
                            continue;
                        }
                        builtin.monitorRuntime().importLegacy(new MonitorEntity(id, payload));
                    } catch (Exception error) {
                        failures++;
                        log.error(
                                "Legacy monitor {} could not be imported; backup retained ({})",
                                id == null ? "<null>" : id,
                                error.getClass().getSimpleName());
                    }
                }
            }
        }
        builtin.monitorRuntime().reloadImported();
        if (failures > 0)
            throw new IllegalStateException(
                    "Legacy monitor import failed for " + failures + " records; backup retained");
    }
}
