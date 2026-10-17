package top.focess.veto.bus;

import static org.apache.tomcat.websocket.Constants.BLOCKING_SEND_TIMEOUT_PROPERTY;
import static org.apache.tomcat.websocket.Constants.READ_IDLE_TIMEOUT_MS;

import jakarta.annotation.PreDestroy;
import jakarta.websocket.Session;
import java.io.IOException;
import java.util.List;
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
import top.focess.veto.api.util.Threads;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.ProtocolConnection;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.LoginSessionManager.LoginSession;

/**
 * WebSocket admission and connection registry. Spring supplies receive callbacks; each {@link
 * WebSocketConnection} owns its socket output. Admission and shutdown share the server monitor;
 * application frames reach the shared {@link ProtocolConnection}.
 */
@Component
public final class WebSocketServer extends TextWebSocketHandler {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.bus.WebSocketServer");
    private final @NonNull AgentSessions runtime;
    private final @NonNull SessionService sessions;
    private final @NonNull SessionCommandService commands;
    private final @NonNull LoginSessionManager loginSessions;
    private final @NonNull ConcurrentHashMap<@NonNull String, @NonNull WebSocketConnection>
            connections = new ConcurrentHashMap<>();
    private boolean stopping;

    public WebSocketServer(
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
        var login = login(socket);
        if (login == null) {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason("authentication required"));
            return;
        }
        if (!(socket instanceof NativeWebSocketSession nativeSocket)
                || !(nativeSocket.getNativeSession() instanceof Session session))
            throw new IllegalStateException("Veto WebSocket requires a native Jakarta session");
        session.getUserProperties().put(READ_IDLE_TIMEOUT_MS, 90_000L);
        session.getUserProperties().put(BLOCKING_SEND_TIMEOUT_PROPERTY, 10_000L);
        synchronized (this) {
            if (!stopping && !connections.containsKey(socket.getId())) {
                var connection =
                        new WebSocketConnection(
                                socket,
                                login,
                                loginSessions,
                                runtime,
                                sessions,
                                commands,
                                closed -> {
                                    if (connections.remove(closed.socket.getId(), closed))
                                        log.debug(
                                                "WS: Connection '{}' closed",
                                                closed.socket.getId());
                                });
                connections.put(socket.getId(), connection);
                connection.writer.start();
                return;
            }
        }
        socket.close(CloseStatus.GOING_AWAY);
    }

    @Override
    protected void handleTextMessage(
            @NonNull WebSocketSession socket, @NonNull TextMessage message) {
        var connection = connections.get(socket.getId());
        if (connection == null || connection.socket != socket || !connection.isOpen()) return;
        var decoded = FrameCodec.decode(message.getPayload());
        if (decoded instanceof Frame.ClientFrame frame) connection.protocol.receive(frame);
        else connection.protocol.send(new Frame.Error("Invalid client frame", 0));
    }

    @Override
    public void afterConnectionClosed(
            @NonNull WebSocketSession socket, @NonNull CloseStatus status) {
        var connection = connections.get(socket.getId());
        if (connection != null && connection.socket == socket) connection.stop(status, false);
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession socket, @NonNull Throwable failure) {
        log.warn(
                "WS: Transport error for '{}' ({})",
                socket.getId(),
                failure.getClass().getSimpleName());
        var connection = connections.get(socket.getId());
        if (connection != null && connection.socket == socket)
            connection.stop(CloseStatus.SERVER_ERROR, false);
    }

    @PreDestroy
    void stop() {
        List<@NonNull WebSocketConnection> closing;
        synchronized (this) {
            stopping = true;
            closing = List.copyOf(connections.values());
        }
        for (var connection : closing) connection.stop(CloseStatus.GOING_AWAY, false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        for (var connection : closing) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0)
                Threads.awaitTermination(
                        connection.writer, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            if (connection.writer.isAlive()) {
                log.warn("WS: Output did not stop before shutdown deadline");
                break;
            }
        }
    }

    public int getActiveSessionCount() {
        return (int)
                connections.values().stream()
                        .filter(connection -> connection.isOpen() && connection.socket.isOpen())
                        .count();
    }

    private LoginSession login(@NonNull WebSocketSession socket) {
        var attributes = socket.getAttributes();
        var user = attributes.get(VetoWebSocketAuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE);
        var token = attributes.get(VetoWebSocketAuthInterceptor.LOGIN_TOKEN_ATTRIBUTE);
        if (!(user instanceof UUID id) || !(token instanceof String text)) return null;
        return loginSessions
                .validateToken(text)
                .filter(login -> id.equals(login.userId()))
                .orElse(null);
    }
}
