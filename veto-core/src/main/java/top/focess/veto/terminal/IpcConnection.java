package top.focess.veto.terminal;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.ProtocolConnection;
import top.focess.veto.session.SessionService;
import top.focess.veto.transport.tcp.TcpConnection;

/**
 * Thread-safe owner of one terminal socket, its protocol endpoint and bounded output queue. This
 * monitor protects output admission, the single active send task and logical closure. Socket IO
 * never holds the monitor; the server owns the supplied executor and task shutdown.
 */
final class IpcConnection {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.terminal.IpcConnection");
    private static final int MAX_MESSAGES = 256;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    final @NonNull String identity = UUID.randomUUID().toString();
    final @NonNull ProtocolConnection protocol;
    private final @NonNull TcpConnection transport;
    private final @NonNull Executor workers;
    private final @NonNull Consumer<@NonNull IpcConnection> onClosed;
    private final @NonNull ArrayDeque<@NonNull IpcOutboundFrame> outbox = new ArrayDeque<>();
    private int queuedBytes;
    private boolean stopped;
    private boolean writing;

    // Callbacks are stored until the completed connection is registered and its workers start.
    @SuppressWarnings("methodref.receiver.bound")
    IpcConnection(
            @NonNull TcpConnection transport,
            @NonNull Executor workers,
            @NonNull AgentSessions runtime,
            @NonNull SessionService sessionService,
            @NonNull SessionCommandService commands,
            @NonNull CommandRegistry registry,
            @NonNull Consumer<@NonNull IpcConnection> onClosed) {
        this.transport = transport;
        this.workers = workers;
        this.onClosed = onClosed;
        protocol =
                new ProtocolConnection(
                        identity,
                        runtime,
                        sessionService,
                        commands,
                        registry,
                        null,
                        () -> true,
                        BooleanSupplier::getAsBoolean,
                        this::send,
                        this::stop,
                        Locale.getDefault());
    }

    private synchronized boolean isOpen() {
        return !stopped;
    }

    private boolean send(
            Frame.@NonNull ServerFrame frame, long version, @NonNull BooleanSupplier attached) {
        var payload = FrameCodec.encode(frame);
        synchronized (this) {
            if (stopped) return false;
            if (outbox.size() < MAX_MESSAGES
                    && payload.length <= TcpConnection.MAX_FRAME_BYTES
                    && payload.length <= MAX_BYTES - queuedBytes) {
                outbox.addLast(new IpcOutboundFrame(frame, payload, version, attached));
                queuedBytes += payload.length;
                if (!writing) {
                    writing = true;
                    workers.execute(this::write);
                }
                return true;
            }
        }
        log.warn("Terminal output limit exceeded for '{}'", identity);
        stop(false);
        return false;
    }

    void stop(boolean drain) {
        boolean first;
        boolean closeSocket;
        synchronized (this) {
            first = !stopped;
            stopped = true;
            if (!drain) {
                outbox.clear();
                queuedBytes = 0;
            }
            closeSocket = !drain || !writing;
        }
        if (closeSocket) {
            transport.close();
            onClosed.accept(this);
        }
        if (first) protocol.close();
    }

    void read() {
        try {
            while (isOpen()) {
                var incoming = transport.receive();
                if (!(incoming instanceof Frame.ClientFrame frame))
                    throw new IOException("Expected a client frame");
                protocol.receive(frame);
            }
        } catch (Throwable failure) {
            if (isOpen())
                log.debug("Terminal input closed for '{}' ({})", identity, failure.toString());
            stop(false);
        }
    }

    private void write() {
        try {
            while (true) {
                IpcOutboundFrame pending;
                boolean closing;
                synchronized (this) {
                    pending = outbox.pollFirst();
                    closing = stopped;
                    if (pending == null) writing = false;
                    else queuedBytes -= pending.payload().length;
                }
                if (pending == null) {
                    if (closing) stop(false);
                    return;
                }
                var frame =
                        protocol.authorizedOutput(
                                pending.frame(), pending.version(), pending.attached());
                if (frame != null)
                    transport.send(
                            frame == pending.frame()
                                    ? pending.payload()
                                    : FrameCodec.encode(frame));
            }
        } catch (Throwable failure) {
            if (isOpen())
                log.debug("Terminal output closed for '{}' ({})", identity, failure.toString());
        }
        stop(false);
        synchronized (this) {
            writing = false;
        }
    }

    /**
     * Encoded output retained with the authority and attachment checks required before delivery.
     */
    private record IpcOutboundFrame(
            Frame.@NonNull ServerFrame frame,
            byte @NonNull [] payload,
            long version,
            @NonNull BooleanSupplier attached) {}
}
