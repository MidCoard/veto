package top.focess.veto.terminal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import top.focess.veto.api.util.Threads;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.ProtocolConnection;
import top.focess.veto.session.SessionService;
import top.focess.veto.transport.tcp.TcpConnection;

/**
 * TCP terminal server. Spring owns the listener; this server owns connection tasks. Each client
 * owns its socket, protocol and bounded output queue. Only receiving waits on an idle connection; a
 * serialized output task runs while data is queued. Admission, output and closure are thread-safe.
 */
@Component
@ConditionalOnProperty(name = "veto.terminal.enabled", havingValue = "true")
public final class IpcServer {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.terminal.IpcServer");
    private static final int MAX_MESSAGES = 256;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final @NonNull CommandRegistry registry;
    private final @NonNull AgentSessions runtime;
    private final @NonNull SessionService sessionService;
    private final @NonNull SessionCommandService commands;
    private final @NonNull ServerSocket listener;
    private final @NonNull ConcurrentHashMap<@NonNull String, @NonNull Client> clients =
            new ConcurrentHashMap<>();
    private final @NonNull ExecutorService workers =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("terminal-io-", 0).factory());
    private Thread acceptor;
    private volatile boolean running;

    public IpcServer(
            @NonNull CommandRegistry registry,
            @Qualifier("ipcListener") @NonNull ServerSocket listener,
            @NonNull AgentSessions runtime,
            @NonNull SessionService sessionService,
            @NonNull SessionCommandService commands) {
        this.registry = registry;
        this.listener = listener;
        this.runtime = runtime;
        this.sessionService = sessionService;
        this.commands = commands;
    }

    @PostConstruct
    public synchronized void start() {
        if (acceptor != null) throw new IllegalStateException("Terminal server already started");
        running = true;
        acceptor = Thread.ofVirtual().name("terminal-accept").start(this::accept);
    }

    @PreDestroy
    public void stop() {
        Thread accepting;
        List<@NonNull Client> closing;
        synchronized (this) {
            running = false;
            accepting = acceptor;
            closing = List.copyOf(clients.values());
        }
        // Interrupting a virtual thread in ServerSocket.accept closes the listener and wakes it.
        if (accepting != null) accepting.interrupt();
        for (var client : closing) client.stop(false);
        Threads.awaitTermination(accepting, 0);
        workers.close();
    }

    private void accept() {
        try {
            while (running) {
                admit(listener.accept());
            }
        } catch (Throwable failure) {
            if (running) log.warn("Terminal accept failed", failure);
        }
        running = false;
        for (var client : clients.values()) client.stop(false);
    }

    private void admit(@NonNull Socket socket) {
        TcpConnection transport;
        try {
            transport = TcpConnection.accept(socket);
        } catch (IOException failure) {
            log.debug("Terminal connection initialization failed ({})", failure.toString());
            return;
        }
        Client client = null;
        try {
            synchronized (this) {
                if (running) {
                    client = new Client(transport);
                    clients.put(client.identity, client);
                    workers.execute(client::read);
                } else transport.close();
            }
        } catch (Throwable failure) {
            if (client == null) transport.close();
            else client.stop(false);
            throw failure;
        }
    }

    public void revokeUser(@NonNull UUID userId) {
        for (var client : clients.values()) client.connection.revokeUser(userId);
    }

    /**
     * This monitor protects output admission, the single active send task and logical closure.
     * Socket IO never holds the monitor; the server executor owns task shutdown.
     */
    private final class Client {
        private final @NonNull String identity = UUID.randomUUID().toString();
        private final @NonNull TcpConnection transport;
        private final @NonNull ProtocolConnection connection;
        private final @NonNull ArrayDeque<@NonNull Pending> outbox = new ArrayDeque<>();
        private int queuedBytes;
        private boolean stopped;
        private boolean writing;

        // Callbacks are stored until the completed client is registered and its workers start.
        @SuppressWarnings("methodref.receiver.bound")
        Client(@NonNull TcpConnection transport) {
            this.transport = transport;
            connection =
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
                    outbox.addLast(new Pending(frame, payload, version, attached));
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

        private void stop(boolean drain) {
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
                if (clients.remove(identity, this))
                    log.debug("Terminal client '{}' closed", identity);
            }
            if (first) connection.close();
        }

        private void read() {
            try {
                while (isOpen()) {
                    var incoming = transport.receive();
                    if (!(incoming instanceof Frame.ClientFrame frame))
                        throw new IOException("Expected a client frame");
                    connection.receive(frame);
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
                    Pending pending;
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
                            connection.authorizedOutput(
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
    }

    private record Pending(
            Frame.@NonNull ServerFrame frame,
            byte @NonNull [] payload,
            long version,
            @NonNull BooleanSupplier attached) {}
}
