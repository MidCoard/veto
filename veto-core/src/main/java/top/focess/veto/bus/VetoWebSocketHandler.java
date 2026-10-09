package top.focess.veto.bus;

import static top.focess.veto.util.LogValues.safe;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import top.focess.veto.VetoVersion;
import top.focess.veto.contract.EventFrame;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.Frame.*;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.transport.websocket.WebSocketChannel;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.LoginSessionManager.LoginSession;
import top.focess.veto.veto.VetoGateway;

/**
 * Server-side WebSocket handler for the Veto Bus (/ws/veto/bus). Clients (UI, MCP servers, workers)
 * connect here for real-time payload streaming. Routes DAG payloads, streams veto results, and
 * handles heartbeats.
 */
@Component
public class VetoWebSocketHandler extends TextWebSocketHandler {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.bus.VetoWebSocketHandler");

    private final @NonNull VetoGateway vetoGateway;
    private final @NonNull SessionRepository sessionRepository;
    private final @NonNull LoginSessionManager loginSessions;

    private final @NonNull CopyOnWriteArrayList<@NonNull WebSocketSession> webSocketSessions =
            new CopyOnWriteArrayList<>();
    private final @NonNull ConcurrentHashMap<@NonNull String, @NonNull String> sessionRoutes =
            new ConcurrentHashMap<>();
    private final @NonNull ConcurrentHashMap<@NonNull String, @NonNull UUID> sessionUsers =
            new ConcurrentHashMap<>();

    private final @NonNull ConcurrentHashMap<@NonNull String, WebSocketChannel.@NonNull Server>
            channels = new ConcurrentHashMap<>();

    private final @NonNull AtomicLong messageCounter = new AtomicLong(0);

    /** Creates the handler with its JSON codec, veto gateway, and session registry. */
    public VetoWebSocketHandler(
            @NonNull VetoGateway vetoGateway,
            @NonNull SessionRepository sessionRepository,
            @NonNull LoginSessionManager loginSessions) {
        this.vetoGateway = vetoGateway;
        this.sessionRepository = sessionRepository;
        this.loginSessions = loginSessions;
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) throws IOException {
        UUID authenticatedUser = authenticatedUser(session);
        if (authenticatedUser == null) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("authentication required"));
            return;
        }
        webSocketSessions.add(new ConcurrentWebSocketSessionDecorator(session, 10000, 1024 * 1024));
        sessionUsers.put(session.getId(), authenticatedUser);
        log.info("WS Bus: Authenticated client '{}' connected", session.getId());

        channels.put(
                session.getId(),
                new WebSocketChannel.Server(
                        session.getId(),
                        text -> sendTo(session, new TextMessage(text)),
                        session::close));
        sendJson(session, new Frame.Welcome(Frame.PROTOCOL_VERSION, 0, VetoVersion.VERSION));
    }

    @Override
    protected void handleTextMessage(
            @NonNull WebSocketSession session, @NonNull TextMessage message) throws IOException {
        if (authenticatedUser(session) == null) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("authentication expired"));
            return;
        }
        long seq = messageCounter.incrementAndGet();
        var channel = channels.get(session.getId());
        if (channel == null) return;
        if (!channel.accept(message.getPayload())) {
            sendJson(session, new Frame.Error("Invalid client frame", 0));
            return;
        }
        var received = channel.recv(0);
        if (received == null) return;
        Frame frame = received.frame();
        switch (frame) {
            case Frame.Hello hello -> {
                if (hello.version() != Frame.PROTOCOL_VERSION)
                    sendJson(session, new Frame.Error("Unsupported protocol version", hello.seq()));
                else
                    sendJson(
                            session,
                            new Frame.Welcome(
                                    Frame.PROTOCOL_VERSION, hello.seq(), VetoVersion.VERSION));
            }
            case Frame.Bye ignored -> session.close(CloseStatus.NORMAL);
            case Frame.Heartbeat heartbeat ->
                    sendJson(session, new Frame.HeartbeatAck(heartbeat.seq(), Instant.now()));
            case Frame.DagPayload dag -> {
                sendJson(session, new Frame.Received(dag.data().getTaskType(), seq, Instant.now()));
                broadcast(new Frame.DagPayload(dag.data(), session.getId()), session.getId());
            }
            case Frame.Process process -> handleVetoProcess(session, process, seq);
            case Frame.Subscribe subscribe -> {
                String topic = subscribe.topic();
                if (topic == null) topic = "all";
                sessionRoutes.put(session.getId(), "sub:" + topic);
                sendJson(session, new Frame.Subscribed(topic, Instant.now()));
            }
            case Frame.Unsubscribe ignored -> {
                sessionRoutes.remove(session.getId());
                sendJson(session, new Frame.Unsubscribed(Instant.now()));
            }
            default ->
                    sendJson(
                            session,
                            new Frame.Error(
                                    "Unsupported frame on this connection",
                                    frame instanceof Frame.SeqRequest request ? request.seq() : 0));
        }
    }

    private void handleVetoProcess(
            @NonNull WebSocketSession session, Frame.@NonNull Process process, long seq) {
        String payload = process.payload();
        if (payload.isEmpty()) {
            sendJson(session, new Frame.Error("payload field is required", 0));
            return;
        }
        String dagPayloadId = process.dagPayloadId();
        if (dagPayloadId == null) dagPayloadId = "ws-" + seq;
        String requestId = process.requestId();
        if (requestId == null) requestId = "ws-req-" + seq;
        String componentSource = process.componentSource();
        if (componentSource == null) componentSource = "WS-Client";
        var result = vetoGateway.processOutbound(payload, dagPayloadId, requestId, componentSource);
        sendJson(
                session,
                new Frame.VetoResult(
                        seq,
                        result.decision().name(),
                        result.processedPayload(),
                        result.reason(),
                        result.redactionCount(),
                        result.isAllowed(),
                        Instant.now()));
    }

    @Override
    public void afterConnectionClosed(
            @NonNull WebSocketSession session, @NonNull CloseStatus status) {
        removeChannel(session.getId());
        webSocketSessions.removeIf(candidate -> candidate.getId().equals(session.getId()));
        sessionRoutes.remove(session.getId());
        sessionUsers.remove(session.getId());
        log.info(
                "WS Bus: Client '{}' disconnected (code={}, reason='{}')",
                session.getId(),
                status.getCode(),
                safe(status.getReason()));
    }

    @Override
    public void handleTransportError(
            @NonNull WebSocketSession session, @NonNull Throwable exception) {
        log.error(
                "WS Bus: Transport error for '{}': {}",
                session.getId(),
                safe(exception.getMessage()));
        removeChannel(session.getId());
        webSocketSessions.removeIf(candidate -> candidate.getId().equals(session.getId()));
        sessionRoutes.remove(session.getId());
        sessionUsers.remove(session.getId());
    }

    /** Broadcast a message to all connected clients except the sender. */
    public void broadcast(Frame.@NonNull DagPayload message, @NonNull String excludeSessionId) {
        UUID senderUser = sessionUsers.get(excludeSessionId);
        if (senderUser == null) {
            return;
        }
        for (WebSocketSession s : webSocketSessions) {
            String route = sessionRoutes.get(s.getId());
            String messageType = "dag.payload";
            boolean acceptsRoute =
                    route == null
                            || "all".equals(route)
                            || "sub:all".equals(route)
                            || route.equals(messageType)
                            || route.equals("sub:" + messageType);
            if (s.isOpen()
                    && !s.getId().equals(excludeSessionId)
                    && senderUser.equals(sessionUsers.get(s.getId()))
                    && acceptsRoute) {
                try {
                    sendJson(s, message);
                } catch (RuntimeException e) {
                    log.warn("WS Bus: Failed to send broadcast to '{}'", s.getId(), e);
                }
            }
        }
    }

    /** Sends one agent frame only to authenticated connections that own its session. */
    public void sendFrame(@NonNull EventFrame frame) {
        UUID sessionId = frame.sessionId();
        if (sessionId == null) return;
        UUID userId =
                sessionRepository
                        .findById(sessionId.toString())
                        .map(session -> session.getUserId())
                        .orElse(null);
        if (userId == null) {
            log.warn("WS Bus: Dropped frame for unknown session {}", sessionId);
            return;
        }
        for (WebSocketSession session : webSocketSessions) {
            if (session.isOpen() && userId.equals(sessionUsers.get(session.getId()))) {
                try {
                    sendJson(session, frame);
                } catch (RuntimeException e) {
                    log.warn("WS Bus: Failed to send frame to '{}'", session.getId(), e);
                }
            }
        }
    }

    private void removeChannel(@NonNull String identity) {
        var channel = channels.remove(identity);
        if (channel != null) {
            try {
                channel.close();
            } catch (RuntimeException error) {
                log.warn("WS Bus: Failed to close '{}'", identity, error);
            }
        }
    }

    private void sendJson(@NonNull WebSocketSession session, Frame.@NonNull ServerFrame data) {
        var channel = channels.get(session.getId());
        if (channel == null || !session.isOpen()) return;
        try {
            channel.send(session.getId(), data);
        } catch (RuntimeException error) {
            log.warn("WS Bus: Failed to send to '{}'", session.getId(), error);
        }
    }

    private void sendTo(@NonNull WebSocketSession target, @NonNull TextMessage message)
            throws IOException {
        if (authenticatedUser(target) == null) {
            target.close(CloseStatus.POLICY_VIOLATION.withReason("authentication expired"));
            return;
        }
        WebSocketSession wrapped =
                webSocketSessions.stream()
                        .filter(candidate -> candidate.getId().equals(target.getId()))
                        .findFirst()
                        .orElse(null);
        if (wrapped == null) return;
        try {
            wrapped.sendMessage(message);
        } catch (RuntimeException | IOException error) {
            try {
                wrapped.close(CloseStatus.SERVER_ERROR);
            } catch (IOException closeError) {
                log.debug("Could not close failed socket", closeError);
            }
            throw error;
        }
    }

    public int getActiveSessionCount() {
        return (int) webSocketSessions.stream().filter(WebSocketSession::isOpen).count();
    }

    public long getTotalMessages() {
        return messageCounter.get();
    }

    private UUID authenticatedUser(@NonNull WebSocketSession session) {
        Object value =
                session.getAttributes()
                        .get(VetoWebSocketAuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE);
        Object token =
                session.getAttributes().get(VetoWebSocketAuthInterceptor.LOGIN_TOKEN_ATTRIBUTE);
        if (!(value instanceof UUID user) || !(token instanceof String text)) return null;
        return loginSessions
                .validateToken(text)
                .filter(authenticated -> user.equals(authenticated.userId()))
                .map(LoginSession::userId)
                .orElse(null);
    }
}
