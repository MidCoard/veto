package top.focess.veto.bus;

import static org.apache.tomcat.websocket.Constants.BLOCKING_SEND_TIMEOUT_PROPERTY;
import static org.apache.tomcat.websocket.Constants.READ_IDLE_TIMEOUT_MS;

import jakarta.annotation.PreDestroy;
import jakarta.websocket.Session;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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
 * WebSocket transport owner. Spring supplies receive callbacks; each Client owns its socket output.
 * Both this server and IpcServer delegate application frames to ProtocolConnection.
 */
@Component
public final class WebSocketServer extends TextWebSocketHandler {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.bus.WebSocketServer");
    private static final int MAX_MESSAGES = 256;
    private static final int MAX_BYTES = 1024 * 1024;
    private final @NonNull AgentSessions runtime;
    private final @NonNull SessionService sessions;
    private final @NonNull SessionCommandService commands;
    private final @NonNull LoginSessionManager loginSessions;
    private final @NonNull ConcurrentHashMap<@NonNull String, @NonNull Client> connections =
            new ConcurrentHashMap<>();
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
                var client = new Client(socket, login);
                connections.put(socket.getId(), client);
                client.writer.start();
                return;
            }
        }
        socket.close(CloseStatus.GOING_AWAY);
    }

    @Override
    protected void handleTextMessage(
            @NonNull WebSocketSession socket, @NonNull TextMessage message) {
        var client = connections.get(socket.getId());
        if (client == null || client.socket != socket || !client.isOpen()) return;
        var decoded = FrameCodec.decode(message.getPayload());
        if (decoded instanceof Frame.ClientFrame frame) client.connection.receive(frame);
        else client.connection.send(new Frame.Error("Invalid client frame", 0));
    }

    @Override
    public void afterConnectionClosed(
            @NonNull WebSocketSession socket, @NonNull CloseStatus status) {
        var client = connections.get(socket.getId());
        if (client != null && client.socket == socket) client.stop(status, false);
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession socket, @NonNull Throwable failure) {
        log.warn(
                "WS: Transport error for '{}' ({})",
                socket.getId(),
                failure.getClass().getSimpleName());
        var client = connections.get(socket.getId());
        if (client != null && client.socket == socket) client.stop(CloseStatus.SERVER_ERROR, false);
    }

    @PreDestroy
    void stop() {
        List<@NonNull Client> closing;
        synchronized (this) {
            stopping = true;
            closing = List.copyOf(connections.values());
        }
        for (var client : closing) client.stop(CloseStatus.GOING_AWAY, false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        for (var client : closing) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0)
                Threads.awaitTermination(
                        client.writer, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            if (client.writer.isAlive()) {
                log.warn("WS: Output did not stop before shutdown deadline");
                break;
            }
        }
    }

    public int getActiveSessionCount() {
        return (int)
                connections.values().stream()
                        .filter(client -> client.isOpen() && client.socket.isOpen())
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

    /**
     * Thread-safe output admission and closure share this monitor. Only the virtual writer sends or
     * closes the socket; it never holds the monitor during IO. Logical closure detaches the
     * protocol immediately, while the registry retains this client until its writer exits.
     */
    private final class Client {
        private final @NonNull WebSocketSession socket;
        private final @NonNull LoginSession login;
        private final @NonNull ProtocolConnection connection;
        private final @NonNull Thread writer;
        private final @NonNull ArrayDeque<@NonNull Pending> outbox = new ArrayDeque<>();
        private int queuedBytes;
        private CloseStatus closeStatus;

        // These callbacks are stored, not invoked, until the completed client is registered.
        @SuppressWarnings("methodref.receiver.bound")
        Client(@NonNull WebSocketSession socket, @NonNull LoginSession login) {
            this.socket = socket;
            this.login = login;
            var headers = socket.getHandshakeHeaders();
            var languages =
                    headers == null ? List.<Locale>of() : headers.getAcceptLanguageAsLocales();
            var locale = languages.isEmpty() ? Locale.getDefault() : languages.getFirst();
            connection =
                    new ProtocolConnection(
                            socket.getId(),
                            runtime,
                            sessions,
                            commands,
                            null,
                            login.userId(),
                            this::authenticated,
                            operation ->
                                    loginSessions.whileAuthenticated(
                                            login.token(), login.userId(), operation),
                            this::send,
                            this::finish,
                            locale);
            writer = Thread.ofVirtual().name("ws-output-" + socket.getId()).unstarted(this::write);
        }

        private boolean authenticated() {
            if (loginSessions
                    .validateToken(login.token())
                    .filter(current -> current == login)
                    .isPresent()) return true;
            stop(CloseStatus.POLICY_VIOLATION.withReason("authentication expired"), false);
            return false;
        }

        private synchronized boolean isOpen() {
            return closeStatus == null;
        }

        private boolean send(
                Frame.@NonNull ServerFrame frame, long version, @NonNull BooleanSupplier attached) {
            var message = new TextMessage(FrameCodec.encode(frame));
            synchronized (this) {
                if (closeStatus != null) return false;
                if (outbox.size() < MAX_MESSAGES
                        && message.getPayloadLength() <= MAX_BYTES - queuedBytes) {
                    outbox.addLast(new Pending(message, attached));
                    queuedBytes += message.getPayloadLength();
                    notifyAll();
                    return true;
                }
            }
            stop(
                    CloseStatus.SESSION_NOT_RELIABLE.withReason("Outbound buffer limit exceeded"),
                    false);
            return false;
        }

        private void finish(boolean drain) {
            stop(drain ? CloseStatus.PROTOCOL_ERROR : CloseStatus.NORMAL, drain);
        }

        private void stop(@NonNull CloseStatus status, boolean drain) {
            boolean first;
            synchronized (this) {
                first = closeStatus == null;
                if (first) closeStatus = status;
                if (!drain) {
                    outbox.clear();
                    queuedBytes = 0;
                }
                notifyAll();
            }
            if (first) connection.close();
        }

        private synchronized Pending next() throws InterruptedException {
            while (outbox.isEmpty() && closeStatus == null) wait();
            var pending = outbox.pollFirst();
            if (pending != null) queuedBytes -= pending.message().getPayloadLength();
            return pending;
        }

        private void write() {
            var status = CloseStatus.NORMAL;
            try {
                Pending pending;
                while ((pending = next()) != null) {
                    if (!socket.isOpen() || !authenticated()) break;
                    if (pending.attached().getAsBoolean()) socket.sendMessage(pending.message());
                }
            } catch (Throwable failure) {
                log.warn(
                        "WS: Output failed for '{}' ({})",
                        socket.getId(),
                        failure.getClass().getSimpleName());
                status = CloseStatus.SERVER_ERROR;
            }
            stop(status, false);
            synchronized (this) {
                var reason = closeStatus;
                if (reason != null) status = reason;
            }
            try {
                if (socket.isOpen()) socket.close(status);
            } catch (Throwable failure) {
                log.debug(
                        "WS: Could not close '{}' ({})",
                        socket.getId(),
                        failure.getClass().getSimpleName());
            }
            if (connections.remove(socket.getId(), this))
                log.debug("WS: Client '{}' closed", socket.getId());
        }
    }

    private record Pending(@NonNull TextMessage message, @NonNull BooleanSupplier attached) {}
}
