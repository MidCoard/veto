package top.focess.veto.bus;

import static top.focess.veto.util.LogValues.safe;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
import top.focess.veto.contract.EventFrame;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.LoginSessionManager.LoginSession;
import top.focess.veto.veto.VetoGateway;

/**
 * Authenticated browser endpoint registered by {@link WebSocketConfig} at /ws/veto/bus. Spring
 * callbacks decode shared frames directly; {@link DeltaBusBridge} supplies live session events.
 */
@Component
public class VetoWebSocketHandler extends TextWebSocketHandler {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.bus.VetoWebSocketHandler");

    private final @NonNull VetoGateway vetoGateway;
    private final @NonNull SessionRepository sessionRepository;
    private final @NonNull LoginSessionManager loginSessions;
    private final @NonNull ConcurrentHashMap<@NonNull String, @NonNull Connection> connections =
            new ConcurrentHashMap<>();
    private final @NonNull AtomicLong messageCounter = new AtomicLong();

    public VetoWebSocketHandler(
            @NonNull VetoGateway vetoGateway,
            @NonNull SessionRepository sessionRepository,
            @NonNull LoginSessionManager loginSessions) {
        this.vetoGateway = vetoGateway;
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
        send(connection, new Frame.Welcome(Frame.PROTOCOL_VERSION, 0, VetoVersion.VERSION));
    }

    @Override
    protected void handleTextMessage(
            @NonNull WebSocketSession socket, @NonNull TextMessage message) {
        var connection = connections.get(socket.getId());
        if (connection == null || !isAuthenticated(connection)) return;
        long sequence = messageCounter.incrementAndGet();
        var decoded = FrameCodec.decode(message.getPayload());
        if (!(decoded instanceof Frame.ClientFrame frame)) {
            send(connection, new Frame.Error("Invalid client frame", 0));
            return;
        }
        switch (frame) {
            case Frame.Hello hello -> {
                if (hello.version() != Frame.PROTOCOL_VERSION)
                    send(connection, new Frame.Error("Unsupported protocol version", hello.seq()));
                else
                    send(connection, new Frame.Welcome(
                            Frame.PROTOCOL_VERSION, hello.seq(), VetoVersion.VERSION));
            }
            case Frame.Bye ignored -> close(connection, CloseStatus.NORMAL);
            case Frame.Heartbeat heartbeat ->
                    send(connection, new Frame.HeartbeatAck(heartbeat.seq(), Instant.now()));
            case Frame.DagPayload dag -> {
                send(connection, new Frame.Received(dag.data().taskType(), sequence, Instant.now()));
                broadcast(connection, new Frame.DagPayload(dag.data(), socket.getId()));
            }
            case Frame.Process process -> handleVetoProcess(connection, process, sequence);
            case Frame.Subscribe subscribe -> {
                String topic = subscribe.topic();
                if (topic == null) topic = "all";
                connection.topic = topic;
                send(connection, new Frame.Subscribed(topic, Instant.now()));
            }
            case Frame.Unsubscribe ignored -> {
                connection.topic = null;
                send(connection, new Frame.Unsubscribed(Instant.now()));
            }
            default -> send(connection, new Frame.Error(
                    "Unsupported frame on this connection",
                    frame instanceof Frame.SeqRequest request ? request.seq() : 0));
        }
    }

    private void handleVetoProcess(
            @NonNull Connection connection, Frame.@NonNull Process process, long sequence) {
        String payload = process.payload();
        if (payload.isEmpty()) {
            send(connection, new Frame.Error("payload field is required", 0));
            return;
        }
        String dagPayloadId = process.dagPayloadId();
        if (dagPayloadId == null) dagPayloadId = "ws-" + sequence;
        String requestId = process.requestId();
        if (requestId == null) requestId = "ws-req-" + sequence;
        String componentSource = process.componentSource();
        if (componentSource == null) componentSource = "WS-Client";
        var result = vetoGateway.processOutbound(payload, dagPayloadId, requestId, componentSource);
        send(connection, new Frame.VetoResult(
                sequence, result.decision().name(), result.processedPayload(), result.reason(),
                result.redactionCount(), result.isAllowed(), Instant.now()));
    }

    @Override
    public void afterConnectionClosed(
            @NonNull WebSocketSession socket, @NonNull CloseStatus status) {
        connections.remove(socket.getId());
        log.debug("WS Bus: Client '{}' disconnected (code={}, reason='{}')",
                socket.getId(), status.getCode(), safe(status.getReason()));
    }

    @Override
    public void handleTransportError(
            @NonNull WebSocketSession socket, @NonNull Throwable failure) {
        log.warn("WS Bus: Transport error for '{}' ({})",
                socket.getId(), failure.getClass().getSimpleName());
        var connection = connections.get(socket.getId());
        if (connection != null) close(connection, CloseStatus.SERVER_ERROR);
    }

    private void broadcast(@NonNull Connection sender, Frame.@NonNull DagPayload frame) {
        for (var recipient : connections.values()) {
            String topic = recipient.topic;
            if (recipient != sender && sender.user.equals(recipient.user)
                    && (topic == null || "all".equals(topic) || "dag.payload".equals(topic))) {
                send(recipient, frame);
            }
        }
    }

    /** Sends session events only to authenticated connections belonging to the session owner. */
    public void sendFrame(@NonNull EventFrame frame) {
        UUID sessionId = frame.sessionId();
        if (sessionId == null) return;
        UUID user = sessionRepository.findById(sessionId.toString())
                .map(session -> session.getUserId()).orElse(null);
        if (user == null) return;
        for (var connection : connections.values()) {
            if (user.equals(connection.user)) send(connection, frame);
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
            log.warn("WS Bus: Failed to send to '{}' ({})",
                    socket.getId(), failure.getClass().getSimpleName());
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
            log.debug("WS Bus: Could not close '{}' ({})",
                    connection.socket.getId(), failure.getClass().getSimpleName());
        }
    }

    public int getActiveSessionCount() {
        return (int) connections.values().stream().filter(c -> c.socket.isOpen()).count();
    }

    public long getTotalMessages() {
        return messageCounter.get();
    }

    private UUID authenticatedUser(@NonNull WebSocketSession socket) {
        Object value = socket.getAttributes()
                .get(VetoWebSocketAuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE);
        Object token = socket.getAttributes().get(VetoWebSocketAuthInterceptor.LOGIN_TOKEN_ATTRIBUTE);
        if (!(value instanceof UUID user) || !(token instanceof String text)) return null;
        return loginSessions.validateToken(text)
                .filter(authenticated -> user.equals(authenticated.userId()))
                .map(LoginSession::userId).orElse(null);
    }

    private static final class Connection {
        private final @NonNull WebSocketSession socket;
        private final @NonNull UUID user;
        private volatile String topic;

        private Connection(@NonNull WebSocketSession socket, @NonNull UUID user) {
            this.socket = new ConcurrentWebSocketSessionDecorator(socket, 10_000, 1024 * 1024);
            this.user = user;
        }
    }
}
