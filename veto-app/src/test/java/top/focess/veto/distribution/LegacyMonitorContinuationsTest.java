package top.focess.veto.distribution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import top.focess.veto.builtin.monitor.MonitorRecord;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.model.AgentEntity;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;

@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
class LegacyMonitorContinuationsTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copiesOnlyMatchingCheckpointAndPreservesNewerBudgetOnRerun(boolean hasAllowance) {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var sql = new JdbcTemplate(source);
        sql.execute(
                "CREATE TABLE agent_request_continuations (id VARCHAR(1024) PRIMARY KEY, task TEXT, consumed_calls BIGINT, granted_calls BIGINT)");
        @NonNull PluginManager plugins = mock();
        @NonNull SessionRepository sessions = mock();
        @NonNull AgentInstanceRepository agents = mock();
        @NonNull SessionPlugins selection = mock();
        var session = new SessionEntity("owner", "test");
        @NonNull AgentEntity agent = mock();
        when(agent.getSessionId()).thenReturn(session.getId());
        when(agents.findById("agent")).thenReturn(Optional.of(agent));
        when(selection.includes(session.getId(), "top.focess.builtin")).thenReturn(true);
        var event =
                new MonitorRecord.Event("event", "monitor", "TIME_ONCE", "notice", Instant.EPOCH);
        var record =
                new MonitorRecord(
                        "monitor",
                        "owner",
                        session.getId(),
                        "agent",
                        "TIME_ONCE",
                        "purpose",
                        null,
                        Instant.EPOCH,
                        "COMPLETED",
                        Map.of(),
                        List.of(event),
                        Instant.EPOCH);
        var importer = new LegacyMonitorContinuations(source, plugins, sessions, agents, selection);
        String contribution = "top.focess.builtin:monitor-work";
        String prefix = session.getId() + ":5:agent:";
        String oldKey = prefix + "monitor:event";
        String newKey =
                prefix
                        + "plugin-work:"
                        + contribution.length()
                        + ":"
                        + contribution
                        + ":monitor:event";
        sql.update(
                "INSERT INTO agent_request_continuations (id, task, consumed_calls) VALUES (?, ?, ?)",
                oldKey,
                "original task",
                7);
        sql.update(
                "INSERT INTO agent_request_continuations (id, task, consumed_calls) VALUES (?, ?, ?)",
                UUID.randomUUID() + ":5:agent:monitor:event",
                "foreign",
                1);
        if (hasAllowance)
            sql.update(
                    "UPDATE agent_request_continuations SET granted_calls = 12 WHERE id = ?",
                    oldKey);
        importer.migrate(session, record, contribution);
        Long initialCalls =
                sql.queryForObject(
                        "SELECT consumed_calls FROM agent_request_continuations WHERE id = ?",
                        Long.class,
                        newKey);
        if (initialCalls == null) throw new AssertionError("Missing imported checkpoint");
        assertEquals(7L, initialCalls.longValue());
        Long initialGrant =
                sql.queryForObject(
                        "SELECT granted_calls FROM agent_request_continuations WHERE id = ?",
                        Long.class,
                        newKey);
        if (hasAllowance) {
            if (initialGrant == null) throw new AssertionError("Missing imported allowance");
            assertEquals(12L, initialGrant.longValue());
        } else assertNull(initialGrant);
        assertEquals(
                "original task",
                sql.queryForObject(
                        "SELECT task FROM agent_request_continuations WHERE id = ?",
                        String.class,
                        newKey));
        sql.update(
                "UPDATE agent_request_continuations SET consumed_calls = 9 WHERE id = ?", newKey);
        importer.migrate(session, record, contribution);
        Long currentCalls =
                sql.queryForObject(
                        "SELECT consumed_calls FROM agent_request_continuations WHERE id = ?",
                        Long.class,
                        newKey);
        if (currentCalls == null) throw new AssertionError("Missing destination checkpoint");
        assertEquals(9L, currentCalls.longValue());
        Long legacyCalls =
                sql.queryForObject(
                        "SELECT consumed_calls FROM agent_request_continuations WHERE id = ?",
                        Long.class,
                        oldKey);
        if (legacyCalls == null) throw new AssertionError("Missing original checkpoint");
        assertEquals(7L, legacyCalls.longValue());
        Integer rowCount =
                sql.queryForObject(
                        "SELECT COUNT(*) FROM agent_request_continuations", Integer.class);
        if (rowCount == null) throw new AssertionError("Missing checkpoint row count");
        assertEquals(3, rowCount.intValue());
        assertThrows(
                SecurityException.class,
                () -> importer.migrate(session, record, "other:monitor-work"));
        when(agent.getSessionId()).thenReturn(UUID.randomUUID().toString());
        assertThrows(
                SecurityException.class, () -> importer.migrate(session, record, contribution));
        var wrongOwner = new SessionEntity("other", "test");
        when(selection.includes(wrongOwner.getId(), "top.focess.builtin")).thenReturn(true);
        assertThrows(
                SecurityException.class, () -> importer.migrate(wrongOwner, record, contribution));
        verifyNoInteractions(plugins, sessions);
    }
}
