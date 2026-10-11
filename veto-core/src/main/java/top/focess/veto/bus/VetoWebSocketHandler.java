package top.focess.veto.bus;

import static top.focess.veto.util.LogValues.safe;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import top.focess.veto.VetoVersion;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.LoginSessionManager.LoginSession;

/**
 * Authenticated browser endpoint registered by {@link WebSocketConfig} at /ws/veto/bus. Spring
 * callbacks decode shared frames directly; {@link DeltaBusBridge} supplies live session events.
 */
@Component
public class VetoWebSocketHandler extends TextWebSocketHandler {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.bus.VetoWebSocketHandler");

    private final @NonNull AgentService agents;
    private final @NonNull HitlRegistry approvals;
    private final @NonNull SessionRepository sessionRepository;
    private final @NonNull LoginSessionManager loginSessions;
    private final @NonNull ConcurrentHashMap<@NonNull String, @NonNull Connection> connections =
            new ConcurrentHashMap<>();

    public VetoWebSocketHandler(
            @NonNull SessionRepository sessionRepository,
            @NonNull LoginSessionManager loginSessions,
            @NonNull AgentService agents,
            @NonNull HitlRegistry approvals) {
        this.agents = agents;
        this.approvals = approvals;
        this.sessionRepository = sessionRepository;
        this.loginSessions = loginSessions;
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession socket) throws IOException {
        UUID user = authenticatedUser(socket);
        if (user == null) {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason("authentication required"));
            return;
        }
        var connection = new Connection(socket, user);
        connections.put(socket.getId(), connection);
    }

    @Override
    protected void handleTextMessage(
            @NonNull WebSocketSession socket, @NonNull TextMessage message) {
        var connection = connections.get(socket.getId());
        if (connection == null || !isAuthenticated(connection)) return;
        var decoded = FrameCodec.decode(message.getPayload());
        if (!(decoded instanceof Frame.ClientFrame frame)) {
            send(connection, new Frame.Error("Invalid client frame", 0));
            return;
        }
        if (frame instanceof Frame.Hello hello) {
            if (connection.ready || hello.version() != Frame.PROTOCOL_VERSION) {
                send(connection, new Frame.Error("Invalid protocol handshake", hello.seq()));
                close(connection, CloseStatus.PROTOCOL_ERROR);
                return;
            }
            send(
                    connection,
                    new Frame.Welcome(Frame.PROTOCOL_VERSION, hello.seq(), VetoVersion.VERSION));
            connection.ready = true;
            return;
        }
        if (!connection.ready) {
            send(connection, new Frame.Error("Hello required before application frames", 0));
            close(connection, CloseStatus.PROTOCOL_ERROR);
            return;
        }
        switch (frame) {
            case Frame.Bye ignored -> close(connection, CloseStatus.NORMAL);
            case Frame.Heartbeat ignored -> send(connection, new Frame.HeartbeatAck(Instant.now()));
            case Frame.VetoResult result -> resolveVeto(connection, result);
            default ->
                    send(
                            connection,
                            new Frame.Error(
                                    "Unsupported frame on this connection",
                                    frame instanceof Frame.SeqRequest request ? request.seq() : 0));
        }
    }

    private void resolveVeto(@NonNull Connection connection, Frame.@NonNull VetoResult result) {
        var scope = result.scope();
        var session = sessionRepository.findById(scope.session()).orElse(null);
        var primary = session == null ? null : session.getPrimaryAgentId();
        if (session == null
                || !connection.user.equals(scope.userId())
                || !connection.user.equals(session.getUserId())
                || primary == null
                || !approvals.sessionAgents(primary).contains(result.scope().agent())) {
            send(connection, new Frame.Error(scope, null, null, "Approval not found", 0));
            return;
        }
        if (result.option().isBlank()
                || !agents.resolveVeto(
                        result.scope().agent(), result.callId(), result.option(), result.seq())) {
            send(connection, new Frame.Error(scope, null, null, "Approval no longer pending", 0));
            return;
        }
        // Tool execution or refusal produces the normal tool-result deltas.
    }

    @Override
    public void afterConnectionClosed(
            @NonNull WebSocketSession socket, @NonNull CloseStatus status) {
        connections.remove(socket.getId());
        log.debug(
                "WS Bus: Client '{}' disconnected (code={}, reason='{}')",
                socket.getId(),
                status.getCode(),
                safe(status.getReason()));
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession socket, @NonNull Throwable failure) {
        log.warn(
                "WS Bus: Transport error for '{}' ({})",
                socket.getId(),
                failure.getClass().getSimpleName());
        var connection = connections.get(socket.getId());
        if (connection != null) close(connection, CloseStatus.SERVER_ERROR);
    }

    /** Sends session events only to authenticated connections belonging to the session owner. */
    public void sendFrame(Frame.@NonNull ScopedFrame frame) {
        if (!(frame instanceof Frame.ServerFrame outgoing))
            throw new IllegalArgumentException("Only server frames can be published to clients");
        UUID sessionId = frame.sessionId();
        if (sessionId == null) return;
        UUID user =
                sessionRepository
                        .findById(sessionId.toString())
                        .map(session -> session.getUserId())
                        .orElse(null);
        var scope = frame.scope();
        if (user == null || scope == null || !user.equals(scope.userId())) return;
        for (var connection : connections.values()) {
            if (connection.ready && user.equals(connection.user)) send(connection, outgoing);
        }
    }

    private void send(@NonNull Connection connection, Frame.@NonNull ServerFrame frame) {
        var socket = connection.socket;
        if (connections.get(socket.getId()) != connection) return;
        if (!socket.isOpen()) {
            connections.remove(socket.getId(), connection);
            return;
        }
        if (!isAuthenticated(connection)) return;
        try {
            socket.sendMessage(new TextMessage(FrameCodec.encodeString(frame)));
        } catch (IOException | RuntimeException failure) {
            log.warn(
                    "WS Bus: Failed to send to '{}' ({})",
                    socket.getId(),
                    failure.getClass().getSimpleName());
            close(connection, CloseStatus.SERVER_ERROR);
        }
    }

    private boolean isAuthenticated(@NonNull Connection connection) {
        if (connection.user.equals(authenticatedUser(connection.socket))) return true;
        close(connection, CloseStatus.POLICY_VIOLATION.withReason("authentication expired"));
        return false;
    }

    private void close(@NonNull Connection connection, @NonNull CloseStatus status) {
        if (!connections.remove(connection.socket.getId(), connection)) return;
        try {
            connection.socket.close(status);
        } catch (IOException | RuntimeException failure) {
            log.debug(
                    "WS Bus: Could not close '{}' ({})",
                    connection.socket.getId(),
                    failure.getClass().getSimpleName());
        }
    }

    public int getActiveSessionCount() {
        return (int) connections.values().stream().filter(c -> c.socket.isOpen()).count();
    }

    private UUID authenticatedUser(@NonNull WebSocketSession socket) {
        Object value =
                socket.getAttributes()
                        .get(VetoWebSocketAuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE);
        Object token =
                socket.getAttributes().get(VetoWebSocketAuthInterceptor.LOGIN_TOKEN_ATTRIBUTE);
        if (!(value instanceof UUID user) || !(token instanceof String text)) return null;
        return loginSessions
                .validateToken(text)
                .filter(authenticated -> user.equals(authenticated.userId()))
                .map(LoginSession::userId)
                .orElse(null);
    }

    private static final class Connection {
        private final @NonNull WebSocketSession socket;
        private final @NonNull UUID user;
        private volatile boolean ready;

        private Connection(@NonNull WebSocketSession socket, @NonNull UUID user) {
            this.socket = new ConcurrentWebSocketSessionDecorator(socket, 10_000, 1024 * 1024);
            this.user = user;
        }
    }
}
