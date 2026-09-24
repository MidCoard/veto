package top.focess.veto.distribution;

import java.util.UUID;
import javax.sql.DataSource;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.builtin.BuiltinPlugin;
import top.focess.veto.builtin.monitor.MonitorRecord;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;

/** Copies trusted upgrade checkpoints; never starts work or changes either budget. */
@Component
@Order(101)
@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
public final class LegacyMonitorContinuations implements ApplicationRunner {
    private final JdbcTemplate database;
    private final PluginManager plugins;
    private final SessionRepository sessions;
    private final AgentInstanceRepository agents;
    private final SessionPlugins selection;

    public LegacyMonitorContinuations(
            DataSource database,
            PluginManager plugins,
            SessionRepository sessions,
            AgentInstanceRepository agents,
            SessionPlugins selection) {
        this.database = new JdbcTemplate(database);
        this.plugins = plugins;
        this.sessions = sessions;
        this.agents = agents;
        this.selection = selection;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        for (var managed : plugins.plugins()) {
            if (!(managed.implementation() instanceof BuiltinPlugin builtin)) continue;
            String namespace = builtin.identity().id();
            var contributions =
                    plugins.catalog().entries(StandardContributionPoints.AGENT_WORK).stream()
                            .filter(
                                    entry ->
                                            entry.source().namespace().equals(namespace)
                                                    && entry.implementation()
                                                            == builtin.monitorRuntime().service())
                            .toList();
            if (contributions.size() != 1)
                throw new IllegalStateException("Builtin Monitor work identity unavailable");
            String contribution = contributions.getFirst().id().value();
            for (var session : sessions.findAll()) {
                if (!selection.includes(session.getId(), namespace)) continue;
                for (var record :
                        builtin.monitorRuntime().storedRecords().stream()
                                .filter(row -> row.sessionId().equals(session.getId()))
                                .toList()) migrate(session, record, contribution);
            }
        }
    }

    void migrate(SessionEntity session, MonitorRecord record, String contribution) {
        if (!contribution.equals("top.focess.builtin:monitor-work"))
            throw new SecurityException("Not the builtin Monitor contribution");
        if (!selection.includes(session.getId(), "top.focess.builtin")) return;
        if (!session.getId().equals(record.sessionId())
                || !session.getOwner().equals(record.owner()))
            throw new SecurityException("Legacy Monitor scope mismatch");
        var agent =
                agents.findById(record.agentId())
                        .orElseThrow(
                                () -> new IllegalStateException("Legacy Monitor agent missing"));
        if (!agent.getSessionId().equals(session.getId()))
            throw new SecurityException("Legacy Monitor agent scope mismatch");
        for (var event : record.readyEvents()) {
            if (!record.id().equals(event.monitorId()))
                throw new SecurityException("Legacy Monitor event identity mismatch");
            if (event.requestId() != null) continue;
            String oldRequest = "monitor:" + event.id();
            String nextRequest =
                    "plugin-work:" + contribution.length() + ":" + contribution + ":" + oldRequest;
            String prefix =
                    UUID.fromString(session.getId())
                            + ":"
                            + record.agentId().length()
                            + ":"
                            + record.agentId()
                            + ":";
            database.update(
                    "INSERT INTO agent_request_continuations (id, task, consumed_calls, granted_calls) "
                            + "SELECT ?, task, consumed_calls, granted_calls FROM agent_request_continuations WHERE id = ? AND consumed_calls >= 0 "
                            + "AND NOT EXISTS (SELECT 1 FROM agent_request_continuations WHERE id = ?)",
                    prefix + nextRequest,
                    prefix + oldRequest,
                    prefix + nextRequest);
        }
    }
}
