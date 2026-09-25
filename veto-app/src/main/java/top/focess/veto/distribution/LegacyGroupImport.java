package top.focess.veto.distribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.builtin.BuiltinPlugin;
import top.focess.veto.builtin.group.GroupHistoryView;
import top.focess.veto.builtin.group.LegacyGroupHistory;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.session.SessionRecordService;

/** Distribution-only upgrade reader. Never changes the legacy table or activates agents. */
@Component
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public final class LegacyGroupImport implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyGroupImport.class);
    private final DataSource database;
    private final PluginManager plugins;
    private final SessionRepository sessions;
    private final SessionPlugins selection;
    private final SessionRecordService records;
    private final ObjectMapper mapper;
    private final LegacyGroupOwnership ownership;

    public LegacyGroupImport(
            DataSource database,
            PluginManager plugins,
            SessionRepository sessions,
            SessionPlugins selection,
            SessionRecordService records,
            ObjectMapper mapper,
            LegacyGroupOwnership ownership) {
        this.database = database;
        this.plugins = plugins;
        this.sessions = sessions;
        this.selection = selection;
        this.records = records;
        this.mapper = mapper;
        this.ownership = ownership;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        BuiltinPlugin builtin = null;
        for (var plugin : plugins.plugins())
            if (plugin.implementation() instanceof BuiltinPlugin value) builtin = value;
        if (builtin == null) return;
        int failures = 0;
        Map<String, Map<String, GroupHistoryView>> latest = new LinkedHashMap<>();
        Set<String> brokenSessions = new HashSet<>();
        try (var connection = database.getConnection()) {
            boolean exists = false;
            try (var tables =
                    connection.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
                while (tables.next())
                    if ("group_history".equalsIgnoreCase(tables.getString("TABLE_NAME")))
                        exists = true;
            }
            if (exists) {
                try (var query =
                                connection.prepareStatement(
                                        "SELECT id, session_id, recorded_at, payload FROM group_history ORDER BY session_id, recorded_at, id");
                        var rows = query.executeQuery()) {
                    while (rows.next()) {
                        String id = rows.getString(1);
                        String sessionId = rows.getString(2);
                        try {
                            if (id == null || sessionId == null)
                                throw new IllegalArgumentException("Missing legacy identity");
                            if (sessions.findById(sessionId).isEmpty()
                                    || !selection.includes(sessionId, builtin.identity().id()))
                                continue;
                            var timestamp = rows.getTimestamp(3);
                            String payload = rows.getString(4);
                            if (timestamp == null || payload == null)
                                throw new IllegalArgumentException("Missing legacy snapshot");
                            GroupHistoryView view =
                                    mapper.readValue(
                                            payload, ToolDocs.nonNullClass(GroupHistoryView.class));
                            if (view == null)
                                throw new IllegalArgumentException("Null legacy snapshot");
                            builtin.groupRuntime()
                                    .history()
                                    .append(
                                            sessionId,
                                            view,
                                            timestamp.toInstant(),
                                            "group_history:" + id);
                            latest.computeIfAbsent(sessionId, ignored -> new LinkedHashMap<>())
                                    .put(view.id(), view);
                        } catch (Exception error) {
                            if (sessionId != null) brokenSessions.add(sessionId);
                            failures++;
                            log.error(
                                    "Legacy group row {} could not be imported; backup retained ({})",
                                    id == null ? "<null>" : id,
                                    error.getClass().getSimpleName());
                        }
                    }
                }
            }
        }
        for (var session : latest.entrySet()) {
            if (brokenSessions.contains(session.getKey())) continue;
            for (var view : session.getValue().values()) {
                try {
                    ownership.claim(session.getKey(), view, builtin.identity().id());
                } catch (Exception error) {
                    failures++;
                    log.error(
                            "Legacy group {} ownership could not be migrated; history retained ({})",
                            view.id(),
                            error.getClass().getSimpleName());
                }
            }
        }
        for (var session : sessions.findAll()) {
            if (!selection.includes(session.getId(), builtin.identity().id())) continue;
            try {
                var legacy =
                        LegacyGroupHistory.read(
                                records
                                        .load(
                                                session.getId(),
                                                session.getName(),
                                                session.getToolResultPresentation())
                                        .records()
                                        .stream()
                                        .map(
                                                record ->
                                                        new LegacyGroupHistory.SessionRecord(
                                                                record.agentId(),
                                                                record.type(),
                                                                record.turnNumber(),
                                                                record.timestamp(),
                                                                record.payload()))
                                        .toList());
                if (legacy.isEmpty()) continue;
                var history = builtin.groupRuntime().history();
                var durable =
                        history.latestSnapshots(session.getId()).stream()
                                .filter(view -> !view.historical())
                                .toList();
                for (var view : unreplaced(legacy, durable))
                    history.append(
                            session.getId(), view, view.createdAt(), "tool-record:" + view.id());
            } catch (Exception error) {
                failures++;
                log.error(
                        "Legacy group records for session {} could not be imported; source retained ({})",
                        session.getId(),
                        error.getClass().getSimpleName());
            }
        }
        if (failures > 0)
            throw new IllegalStateException(
                    "Legacy group import failed for "
                            + failures
                            + " records or sessions; source retained");
    }

    static List<GroupHistoryView> unreplaced(
            List<GroupHistoryView> legacy, List<GroupHistoryView> durable) {
        return legacy.stream()
                .filter(
                        old -> {
                            Instant next =
                                    legacy.stream()
                                            .filter(
                                                    view ->
                                                            view.leaderId().equals(old.leaderId())
                                                                    && view.createdAt()
                                                                            .isAfter(
                                                                                    old
                                                                                            .createdAt()))
                                            .map(GroupHistoryView::createdAt)
                                            .min(Comparator.naturalOrder())
                                            .orElse(null);
                            return durable.stream()
                                    .noneMatch(
                                            view ->
                                                    view.leaderId().equals(old.leaderId())
                                                            && !view.createdAt()
                                                                    .isBefore(old.createdAt())
                                                            && (next == null
                                                                    || view.createdAt()
                                                                            .isBefore(next)));
                        })
                .toList();
    }
}
