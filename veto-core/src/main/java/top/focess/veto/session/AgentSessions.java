package top.focess.veto.session;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.AgentService;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.session.AgentSession.Attachment;

/** Owns runtime sessions and all frontend attachments under one monitor. */
@Component
public final class AgentSessions {
    private final @NonNull SessionRepository repository;
    private final @NonNull ObjectProvider<AgentService> agents;
    private final @NonNull Map<@NonNull String, @NonNull AgentSession> sessions = new HashMap<>();
    private final @NonNull Map<@NonNull String, @NonNull AgentSession> terminals = new HashMap<>();
    private final @NonNull Map<@NonNull String, @NonNull ProtocolConnection> terminalConnections =
            new HashMap<>();
    private final @NonNull Set<@NonNull ProtocolConnection> browsers = new HashSet<>();

    public AgentSessions(
            @NonNull SessionRepository repository, @NonNull ObjectProvider<AgentService> agents) {
        this.repository = repository;
        this.agents = agents;
    }

    /** Trusted application publication; inbound requests must use requireOwned instead. */
    public synchronized @NonNull AgentSession session(Scope.@NonNull SessionScope scope) {
        var session = sessions.get(scope.session());
        if (session == null) {
            session = new AgentSession(this, scope, agents::getObject);
            sessions.put(scope.session(), session);
            for (var connection : browsers)
                if (scope.userId().equals(connection.userId()))
                    session.connections.putIfAbsent(connection, new Attachment(connection));
        } else if (!session.scope().equals(scope))
            throw new IllegalArgumentException("Session owner mismatch");
        return session;
    }

    public @NonNull AgentSession requireOwned(@NonNull String id, @NonNull UUID user) {
        var entity = repository.findById(id).orElse(null);
        if (entity == null || !user.equals(entity.getUserId()))
            throw new IllegalArgumentException("Session not found");
        return session(new Scope.SessionScope(user, id));
    }

    public synchronized void connectTerminal(
            @NonNull String id, @NonNull ProtocolConnection connection) {
        var previous = terminalConnections.put(id, connection);
        var selected = terminals.get(id);
        if (selected != null) {
            if (previous != null) selected.connections.remove(previous);
            selected.connections.putIfAbsent(connection, new Attachment(connection));
        }
    }

    public synchronized void select(@NonNull String id, Scope.@NonNull SessionScope scope) {
        var selected = session(scope);
        if (selected.closed) throw new IllegalStateException("Session is closed");
        detach(id);
        terminals.put(id, selected);
        var connection = terminalConnections.get(id);
        if (connection != null)
            selected.connections.putIfAbsent(connection, new Attachment(connection));
    }

    public synchronized String selected(@NonNull String id) {
        var selected = terminals.get(id);
        return selected == null ? null : selected.scope().session();
    }

    public synchronized void detach(@NonNull String id) {
        var selected = terminals.remove(id);
        var connection = terminalConnections.get(id);
        if (selected != null && connection != null) selected.connections.remove(connection);
    }

    public synchronized void connectBrowser(@NonNull ProtocolConnection connection) {
        browsers.add(connection);
        for (var session : sessions.values())
            if (!session.closed && session.scope().userId().equals(connection.userId()))
                session.connections.putIfAbsent(connection, new Attachment(connection));
    }

    public synchronized void disconnect(@NonNull ProtocolConnection connection) {
        browsers.remove(connection);
        terminalConnections
                .entrySet()
                .removeIf(
                        entry -> {
                            if (entry.getValue() != connection) return false;
                            terminals.remove(entry.getKey());
                            return true;
                        });
        for (var session : sessions.values()) session.connections.remove(connection);
    }

    public synchronized void detachUser(@NonNull UUID user) {
        for (var id : Set.copyOf(terminals.keySet())) {
            var session = terminals.get(id);
            if (session != null && user.equals(session.scope().userId())) detach(id);
        }
    }

    public synchronized void close(Scope.@NonNull SessionScope scope) {
        var id = scope.session();
        var session = session(scope);
        session.closed = true;
        session.connections.clear();
        terminals.entrySet().removeIf(entry -> entry.getValue().scope().session().equals(id));
    }
}
