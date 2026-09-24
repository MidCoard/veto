package top.focess.veto.distribution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.builtin.BuiltinPlugin;
import top.focess.veto.builtin.group.GroupHistoryStore;
import top.focess.veto.builtin.group.GroupHistoryView;
import top.focess.veto.builtin.group.GroupRuntime;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.ManagedPlugin;
import top.focess.veto.session.SessionRecordService;

@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
class LegacyGroupImportTest {
    @Test
    void importsEveryRowIdempotentlyWithoutMutatingBackupOrInventingRoster() throws Exception {
        var database =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var sql = new JdbcTemplate(database);
        sql.execute(
                "CREATE TABLE group_history (id BIGINT PRIMARY KEY, session_id VARCHAR(255), recorded_at TIMESTAMP, payload TEXT)");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var session = new SessionEntity("owner", "test", "D:/workspace");
        var first = view(UUID.randomUUID().toString(), "leader", 1, false);
        var last =
                new GroupHistoryView(
                        first.id(),
                        first.leaderId(),
                        first.brief(),
                        "DISBANDED",
                        first.createdAt(),
                        List.of(
                                new GroupHistoryView.Node(
                                        "node",
                                        "task",
                                        "mate",
                                        "skill",
                                        List.of(),
                                        "CANCELLED",
                                        "report",
                                        2,
                                        "request",
                                        "dispatch")),
                        false,
                        false,
                        List.of(),
                        Map.of("mate", "member"));
        String firstPayload = mapper.writeValueAsString(first);
        sql.update(
                "INSERT INTO group_history VALUES (?, ?, ?, ?)",
                1,
                session.getId(),
                Timestamp.from(Instant.ofEpochSecond(1)),
                firstPayload);
        sql.update(
                "INSERT INTO group_history VALUES (?, ?, ?, ?)",
                2,
                session.getId(),
                Timestamp.from(Instant.ofEpochSecond(2)),
                mapper.writeValueAsString(last));
        sql.update(
                "INSERT INTO group_history VALUES (?, ?, ?, ?)",
                3,
                "deleted",
                Timestamp.from(Instant.ofEpochSecond(3)),
                "{");
        @NonNull PluginManager plugins = mock();
        @NonNull ManagedPlugin managed = mock();
        @NonNull BuiltinPlugin builtin = mock();
        @NonNull GroupRuntime runtime = mock();
        @NonNull SessionRepository sessions = mock();
        @NonNull SessionPlugins selected = mock();
        @NonNull SessionRecordService records = mock();
        @NonNull PluginStorage storage = mock();
        PluginStorage.@NonNull Store store = mock();
        var scope = new PluginStorage.SessionScope("token", "owner", session.getId());
        Map<String, PluginStorage.Entry> entries = new HashMap<>();
        when(storage.session(scope)).thenReturn(store);
        when(store.get(anyString()))
                .thenAnswer(call -> Optional.ofNullable(entries.get(call.getArgument(0))));
        when(store.put(anyString(), nullable(String.class), any()))
                .thenAnswer(
                        call -> {
                            @NonNull String key = call.getArgument(0);
                            var row =
                                    new PluginStorage.Entry(
                                            key, UUID.randomUUID().toString(), call.getArgument(2));
                            entries.put(key, row);
                            return row;
                        });
        when(store.list(anyString(), nullable(String.class), anyInt()))
                .thenAnswer(
                        call ->
                                new PluginStorage.Page<>(
                                        entries.values().stream()
                                                .filter(
                                                        entry ->
                                                                entry.key()
                                                                        .startsWith(
                                                                                call.getArgument(
                                                                                        0,
                                                                                        String
                                                                                                .class)))
                                                .toList(),
                                        null));
        var history = new GroupHistoryStore(storage);
        history.scope(scope);
        when(plugins.plugins()).thenReturn(List.of(managed));
        when(managed.implementation()).thenReturn(builtin);
        when(builtin.identity()).thenReturn(new PluginIdentity("top.focess.builtin", "1.0.0"));
        when(builtin.groupRuntime()).thenReturn(runtime);
        when(runtime.history()).thenReturn(history);
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(selected.includes(session.getId(), "top.focess.builtin")).thenReturn(true);
        var importer =
                new LegacyGroupImport(
                        database,
                        plugins,
                        sessions,
                        selected,
                        records,
                        mapper,
                        mock(LegacyGroupOwnership.class));
        importer.run(new DefaultApplicationArguments());
        int count = entries.size();
        importer.run(new DefaultApplicationArguments());
        assertEquals(count, entries.size());
        var imported = history.latestSnapshots(session.getId()).getFirst();
        assertEquals(first.id(), imported.id());
        assertEquals(last.nodes(), imported.nodes());
        assertEquals(last.mates(), imported.mates());
        assertEquals(2, imported.changes().size());
        assertEquals("UNKNOWN", imported.changes().getFirst().state());
        assertEquals(
                firstPayload,
                sql.queryForObject("SELECT payload FROM group_history WHERE id = 1", String.class));
        Integer rowCount = sql.queryForObject("SELECT COUNT(*) FROM group_history", Integer.class);
        if (rowCount == null) throw new AssertionError("Missing row count");
        assertEquals(3, rowCount.intValue());
        verifyNoInteractions(records);
        sql.update(
                "INSERT INTO group_history VALUES (?, ?, ?, ?)",
                4,
                session.getId(),
                Timestamp.from(Instant.ofEpochSecond(4)),
                "{");
        assertThrows(
                IllegalStateException.class, () -> importer.run(new DefaultApplicationArguments()));
    }

    @Test
    void legacyReplacementUsesSameLeaderAndHalfOpenCreationWindow() {
        var first = view("legacy:leader:1", "leader", 1, true);
        var second = view("legacy:leader:2", "leader", 3, true);
        var other = view("legacy:other:1", "other", 1, true);
        assertEquals(
                List.of(first, other),
                LegacyGroupImport.unreplaced(
                        List.of(first, second, other),
                        List.of(view(UUID.randomUUID().toString(), "leader", 3, false))));
        assertNull(first.mates());
    }

    private static GroupHistoryView view(
            String id, String leader, long seconds, boolean historical) {
        return new GroupHistoryView(
                id,
                leader,
                "brief",
                "UNKNOWN",
                Instant.ofEpochSecond(seconds),
                List.of(),
                historical,
                false,
                List.of());
    }
}
