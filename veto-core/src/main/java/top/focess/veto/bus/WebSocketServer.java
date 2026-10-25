package top.focess.veto.bus;

import static org.apache.tomcat.websocket.Constants.BLOCKING_SEND_TIMEOUT_PROPERTY;
import static org.apache.tomcat.websocket.Constants.READ_IDLE_TIMEOUT_MS;

import jakarta.annotation.PreDestroy;
import jakarta.websocket.Session;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.ProtocolConnection;
import top.focess.veto.session.ProtocolConnection.CloseReason;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.LoginSessionManager.LoginSession;

/**
 * WebSocket admission and connection registry. Spring supplies receive callbacks; shared protocol
 * endpoints own business handling and output queues. Admission and shutdown share this monitor; the
 * server owns and joins output, request preparation and control tasks.
 */
@Component
public final class WebSocketServer extends TextWebSocketHandler {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.bus.WebSocketServer");
    private final @NonNull AgentSessions runtime;
    private final @NonNull SessionService sessions;
    private final @NonNull SessionCommandService commands;
    private final @NonNull LoginSessionManager loginSessions;
    private final @NonNull ConcurrentHashMap<@NonNull String, @NonNull WebSocketPeer> connections =
            new ConcurrentHashMap<>();
    private final @NonNull ExecutorService workers =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("ws-connection-", 0).factory());
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
        // Native partial-delivery buffer; the complete JSON Frame limit is enforced on assembly.
        socket.setTextMessageSizeLimit(16 * 1024);
        var headers = socket.getHandshakeHeaders();
        var languages = headers == null ? List.<Locale>of() : headers.getAcceptLanguageAsLocales();
        var locale = languages.isEmpty() ? Locale.getDefault() : languages.getFirst();
        synchronized (this) {
            if (!stopping && !connections.containsKey(socket.getId())) {
                var transport = new WebSocketConnection(socket);
                var protocol =
                        new ProtocolConnection(
                                socket.getId(),
                                runtime,
                                sessions,
                                commands,
                                null,
                                login.userId(),
                                () ->
                                        loginSessions
                                                .validateToken(login.token())
                                                .filter(current -> current == login)
                                                .isPresent(),
                                operation ->
                                        loginSessions.whileAuthenticated(
                                                login.token(), login.userId(), operation),
                                workers,
                                transport::send,
                                reason -> {
                                    transport.close(
                                            switch (reason) {
                                                case NORMAL -> CloseStatus.NORMAL;
                                                case PROTOCOL_ERROR -> CloseStatus.PROTOCOL_ERROR;
                                                case IO_ERROR -> CloseStatus.SERVER_ERROR;
                                                case OUTPUT_LIMIT ->
                                                        CloseStatus.SESSION_NOT_RELIABLE.withReason(
                                                                "Outbound buffer limit exceeded");
                                                case SERVER_SHUTDOWN -> CloseStatus.GOING_AWAY;
                                                case UNAUTHORIZED ->
                                                        CloseStatus.POLICY_VIOLATION.withReason(
                                                                "authentication expired");
                                                case FRAME_TOO_LARGE ->
                                                        CloseStatus.TOO_BIG_TO_PROCESS;
                                            });
                                    if (connections.remove(socket.getId()) != null)
                                        log.debug("WS: Connection '{}' closed", socket.getId());
                                },
                                locale);
                connections.put(socket.getId(), new WebSocketPeer(transport, protocol));
                return;
            }
        }
        socket.close(CloseStatus.GOING_AWAY);
    }

    @Override
    public boolean supportsPartialMessages() {
        return true;
    }

    @Override
    protected void handleTextMessage(
            @NonNull WebSocketSession socket, @NonNull TextMessage message) {
        var connection = connections.get(socket.getId());
        if (connection == null
                || connection.transport().socket != socket
                || connection.protocol().isClosed()) return;
        Frame decoded;
        try {
            decoded = connection.transport().receive(message);
        } catch (IOException oversized) {
            connection.protocol().close(CloseReason.FRAME_TOO_LARGE);
            return;
        }
        if (!message.isLast()) return;
        if (decoded instanceof Frame.ClientFrame frame) connection.protocol().receive(frame);
        else connection.protocol().send(new Frame.Error("Invalid client frame", 0));
    }

    @Override
    public void afterConnectionClosed(
            @NonNull WebSocketSession socket, @NonNull CloseStatus status) {
        var connection = connections.get(socket.getId());
        if (connection != null && connection.transport().socket == socket)
            connection.protocol().close();
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession socket, @NonNull Throwable failure) {
        log.warn(
                "WS: Transport error for '{}' ({})",
                socket.getId(),
                failure.getClass().getSimpleName());
        var connection = connections.get(socket.getId());
        if (connection != null && connection.transport().socket == socket)
            connection.protocol().close(CloseReason.IO_ERROR);
    }

    @PreDestroy
    void stop() {
        List<@NonNull WebSocketPeer> closing;
        synchronized (this) {
            stopping = true;
            closing = List.copyOf(connections.values());
        }
        for (var connection : closing) connection.protocol().close(CloseReason.SERVER_SHUTDOWN);
        workers.close();
    }

    public int getActiveSessionCount() {
        return (int)
                connections.values().stream()
                        .filter(
                                connection ->
                                        !connection.protocol().isClosed()
                                                && connection.transport().socket.isOpen())
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

    private record WebSocketPeer(
            @NonNull WebSocketConnection transport, @NonNull ProtocolConnection protocol) {}
}
