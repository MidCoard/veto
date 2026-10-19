package top.focess.veto.bus;

import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import top.focess.veto.api.util.ByteBoundedQueue;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.ProtocolConnection;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.LoginSessionManager.LoginSession;

/**
 * Thread-safe output admission and closure share this monitor. Only the virtual writer sends or
 * closes the socket; it never holds the monitor during IO. Logical closure detaches the protocol
 * immediately, while the registry retains this connection until its writer exits.
 */
final class WebSocketConnection {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.bus.WebSocketConnection");
    private static final int MAX_MESSAGES = 256;
    private static final int MAX_BYTES = 1024 * 1024;
    private final @NonNull LoginSessionManager loginSessions;
    private final @NonNull Consumer<@NonNull WebSocketConnection> onClosed;
    final @NonNull WebSocketSession socket;
    private final @NonNull LoginSession login;
    final @NonNull ProtocolConnection protocol;
    final @NonNull Thread writer;
    private final @NonNull ByteBoundedQueue<@NonNull WebSocketOutboundMessage> outbox =
            new ByteBoundedQueue<>(MAX_MESSAGES, MAX_BYTES);
    private CloseStatus closeStatus;

    // These callbacks are stored, not invoked, until the completed connection is registered.
    @SuppressWarnings({"methodref.receiver.bound", "method.invocation"})
    WebSocketConnection(
            @NonNull WebSocketSession socket,
            @NonNull LoginSession login,
            @NonNull LoginSessionManager loginSessions,
            @NonNull AgentSessions runtime,
            @NonNull SessionService sessions,
            @NonNull SessionCommandService commands,
            @NonNull Consumer<@NonNull WebSocketConnection> onClosed) {
        this.socket = socket;
        this.login = login;
        this.loginSessions = loginSessions;
        this.onClosed = onClosed;
        var headers = socket.getHandshakeHeaders();
        var languages = headers == null ? List.<Locale>of() : headers.getAcceptLanguageAsLocales();
        var locale = languages.isEmpty() ? Locale.getDefault() : languages.getFirst();
        protocol =
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
                        drain ->
                                stop(
                                        drain ? CloseStatus.PROTOCOL_ERROR : CloseStatus.NORMAL,
                                        drain),
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

    synchronized boolean isOpen() {
        return closeStatus == null;
    }

    private boolean send(
            Frame.@NonNull ServerFrame frame, long version, @NonNull BooleanSupplier attached) {
        var message = new TextMessage(FrameCodec.encode(frame));
        synchronized (this) {
            if (closeStatus != null) return false;
            if (outbox.offer(
                    new WebSocketOutboundMessage(message, attached), message.getPayloadLength())) {
                notifyAll();
                return true;
            }
        }
        stop(CloseStatus.SESSION_NOT_RELIABLE.withReason("Outbound buffer limit exceeded"), false);
        return false;
    }

    void stop(@NonNull CloseStatus status, boolean drain) {
        boolean first;
        synchronized (this) {
            first = closeStatus == null;
            if (first) closeStatus = status;
            if (!drain) {
                outbox.clear();
            }
            notifyAll();
        }
        if (first) protocol.close();
    }

    private synchronized WebSocketOutboundMessage next() throws InterruptedException {
        while (outbox.isEmpty() && closeStatus == null) wait();
        return outbox.poll();
    }

    private void write() {
        var status = CloseStatus.NORMAL;
        try {
            WebSocketOutboundMessage pending;
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
        onClosed.accept(this);
    }

    /**
     * Encoded output with an attachment check evaluated by the connection writer before delivery.
     */
    private record WebSocketOutboundMessage(
            @NonNull TextMessage message, @NonNull BooleanSupplier attached) {}
}
