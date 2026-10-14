package top.focess.veto.bus;

import static org.apache.tomcat.websocket.Constants.BLOCKING_SEND_TIMEOUT_PROPERTY;
import static org.apache.tomcat.websocket.Constants.READ_IDLE_TIMEOUT_MS;

import jakarta.annotation.PreDestroy;
import jakarta.websocket.Session;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.ProtocolConnection;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.LoginSessionManager.LoginSession;

/** Authenticated WebSocket adapter. Shared connections own application frame handling. */
@Component
public final class VetoWebSocketHandler extends TextWebSocketHandler {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.bus.VetoWebSocketHandler");
    private final @NonNull AgentSessions runtime;
    private final @NonNull SessionService sessions;
    private final @NonNull SessionCommandService commands;
    private final @NonNull LoginSessionManager loginSessions;
    private final @NonNull ConcurrentHashMap<@NonNull String, @NonNull Peer> connections =
            new ConcurrentHashMap<>();
    private boolean stopping;

    public VetoWebSocketHandler(
            @NonNull SessionService sessions,
            @NonNull SessionCommandService commands,
            @NonNull LoginSessionManager loginSessions,
            @NonNull AgentSessions runtime) {
        this.sessions = sessions;
        this.commands = commands;
        this.loginSessions = loginSessions;
        this.runtime = runtime;
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession socket) throws IOException {
        var user = authenticatedUser(socket);
        if (user == null) {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason("authentication required"));
            return;
        }
        if (!(socket instanceof NativeWebSocketSession nativeSocket)
                || !(nativeSocket.getNativeSession() instanceof Session session))
            throw new IllegalStateException("Veto WebSocket requires a native Jakarta session");
        session.getUserProperties().put(READ_IDLE_TIMEOUT_MS, 90_000L);
        session.getUserProperties().put(BLOCKING_SEND_TIMEOUT_PROPERTY, 10_000L);
        var peer = createPeer(socket, user);
        boolean registered;
        synchronized (this) {
            registered = !stopping && connections.putIfAbsent(socket.getId(), peer) == null;
            if (registered) peer.writer.start();
        }
        if (!registered) socket.close(CloseStatus.GOING_AWAY);
    }

    @Override
    protected void handleTextMessage(
            @NonNull WebSocketSession socket, @NonNull TextMessage message) {
        var peer = connections.get(socket.getId());
        if (peer == null || peer.socket != socket || !peer.writer.isOpen()) return;
        var decoded = FrameCodec.decode(message.getPayload());
        if (decoded instanceof Frame.ClientFrame frame) peer.connection.receive(frame);
        else peer.connection.send(new Frame.Error("Invalid client frame", 0));
    }

    @Override
    public void afterConnectionClosed(
            @NonNull WebSocketSession socket, @NonNull CloseStatus status) {
        var peer = connections.get(socket.getId());
        if (peer != null && peer.socket == socket) peer.close(status);
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession socket, @NonNull Throwable failure) {
        log.warn(
                "WS Bus: Transport error for '{}' ({})",
                socket.getId(),
                failure.getClass().getSimpleName());
        var peer = connections.get(socket.getId());
        if (peer != null && peer.socket == socket) peer.close(CloseStatus.SERVER_ERROR);
    }

    @PreDestroy
    void stop() {
        List<@NonNull Peer> closing;
        synchronized (this) {
            stopping = true;
            closing = List.copyOf(connections.values());
        }
        for (var peer : closing) peer.close(CloseStatus.GOING_AWAY);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        for (var peer : closing) {
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || !peer.writer.awaitTermination(Duration.ofNanos(remaining))) {
                    log.warn("WS Bus: Output writers did not stop before shutdown deadline");
                    break;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public int getActiveSessionCount() {
        return (int)
                connections.values().stream()
                        .filter(peer -> peer.writer.isOpen() && peer.socket.isOpen())
                        .count();
    }

    private UUID authenticatedUser(@NonNull WebSocketSession socket) {
        var value =
                socket.getAttributes()
                        .get(VetoWebSocketAuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE);
        var token = socket.getAttributes().get(VetoWebSocketAuthInterceptor.LOGIN_TOKEN_ATTRIBUTE);
        if (!(value instanceof UUID user) || !(token instanceof String text)) return null;
        return loginSessions
                .validateToken(text)
                .filter(login -> user.equals(login.userId()))
                .map(LoginSession::userId)
                .orElse(null);
    }

    private @NonNull Peer createPeer(@NonNull WebSocketSession socket, @NonNull UUID user) {
        var token = socket.getAttributes().get(VetoWebSocketAuthInterceptor.LOGIN_TOKEN_ATTRIBUTE);
        if (!(token instanceof String text))
            throw new IllegalArgumentException("Login token required");
        var writer =
                new WebSocketWriter(
                        socket, () -> user.equals(authenticatedUser(socket)), this::removed);
        var headers = socket.getHandshakeHeaders();
        var languages = headers == null ? List.<Locale>of() : headers.getAcceptLanguageAsLocales();
        var locale = languages.isEmpty() ? Locale.getDefault() : languages.getFirst();
        var connection =
                new ProtocolConnection(
                        socket.getId(),
                        runtime,
                        sessions,
                        commands,
                        null,
                        user,
                        () -> authenticated(user, socket, writer),
                        operation -> loginSessions.whileAuthenticated(text, user, operation),
                        (frame, version, attached) -> writer.send(frame, attached),
                        drain -> {
                            if (drain) writer.finish(CloseStatus.PROTOCOL_ERROR);
                            else writer.close(CloseStatus.NORMAL);
                        },
                        locale);
        return new Peer(socket, connection, writer);
    }

    private boolean authenticated(
            @NonNull UUID user, @NonNull WebSocketSession socket, @NonNull WebSocketWriter writer) {
        if (user.equals(authenticatedUser(socket))) return true;
        writer.close(CloseStatus.POLICY_VIOLATION.withReason("authentication expired"));
        return false;
    }

    private void removed(@NonNull WebSocketWriter writer) {
        var peer = connections.get(writer.socket.getId());
        if (peer != null
                && peer.writer == writer
                && connections.remove(writer.socket.getId(), peer)) peer.connection.close();
    }

    private record Peer(
            @NonNull WebSocketSession socket,
            @NonNull ProtocolConnection connection,
            @NonNull WebSocketWriter writer) {
        void close(@NonNull CloseStatus status) {
            writer.close(status);
            connection.close();
        }
    }
}
