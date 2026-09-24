package top.focess.veto.distribution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.builtin.BuiltinPlugin;
import top.focess.veto.builtin.monitor.MonitorEntity;
import top.focess.veto.builtin.monitor.MonitorRecord;
import top.focess.veto.builtin.monitor.MonitorRuntime;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.plugin.runtime.ManagedPlugin;

@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
class LegacyMonitorImportTest {
    @Test
    void validatesOwnershipPreservesLegacyBackupAndReportsBrokenRows() throws Exception {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var sql = new JdbcTemplate(source);
        sql.execute(
                "CREATE TABLE agent_monitors (id VARCHAR(255) PRIMARY KEY, payload TEXT NOT NULL)");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var session = new SessionEntity("owner", "test", "D:/workspace");
        var record =
                new MonitorRecord(
                        "old",
                        "owner",
                        session.getId(),
                        "agent",
                        "TIME_ONCE",
                        "notice",
                        null,
                        Instant.now(),
                        "ACTIVE",
                        Map.of(),
                        List.of(),
                        Instant.now());
        String payload = mapper.writeValueAsString(record);
        sql.update("INSERT INTO agent_monitors(id, payload) VALUES (?, ?)", "old", payload);
        sql.update("INSERT INTO agent_monitors(id, payload) VALUES (?, ?)", "broken", "{");
        @NonNull PluginManager plugins = mock();
        @NonNull ManagedPlugin managed = mock();
        @NonNull BuiltinPlugin builtin = mock();
        @NonNull MonitorRuntime runtime = mock();
        @NonNull SessionRepository sessions = mock();
        @NonNull SessionPlugins selected = mock();
        when(plugins.plugins()).thenReturn(List.of(managed));
        when(managed.implementation()).thenReturn(builtin);
        when(builtin.identity()).thenReturn(new PluginIdentity("top.focess.builtin", "1.0.0"));
        when(builtin.monitorRuntime()).thenReturn(runtime);
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(selected.includes(session.getId(), "top.focess.builtin")).thenReturn(true);
        var importer = new LegacyMonitorImport(source, plugins, sessions, selected, mapper);
        assertThrows(
                IllegalStateException.class, () -> importer.run(new DefaultApplicationArguments()));
        ArgumentCaptor<MonitorEntity> captured =
                ArgumentCaptor.forClass(ToolDocs.nonNullClass(MonitorEntity.class));
        verify(runtime).importLegacy(captured.capture());
        assertEquals("old", captured.getValue().getId());
        assertEquals(payload, captured.getValue().getPayload());
        Integer count = sql.queryForObject("SELECT COUNT(*) FROM agent_monitors", Integer.class);
        if (count == null) throw new AssertionError("Missing legacy row count");
        assertEquals(2, count.intValue());
        assertEquals(
                payload,
                sql.queryForObject(
                        "SELECT payload FROM agent_monitors WHERE id = 'old'", String.class));
        verify(runtime).reloadImported();
    }
}
