package top.focess.veto.terminal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import top.focess.veto.api.util.Threads;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.Frame;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.ProtocolConnection;
import top.focess.veto.session.ProtocolConnection.CloseReason;
import top.focess.veto.session.SessionService;
import top.focess.veto.transport.tcp.TcpConnection;

/**
 * Accepts terminal TCP connections and delivers decoded Frames to the shared protocol endpoint.
 * Admission and shutdown share this monitor. The server owns and joins all connection tasks; each
 * ProtocolConnection owns its output queue and closes its native transport once.
 */
@Component
@ConditionalOnProperty(name = "veto.terminal.enabled", havingValue = "true")
public final class TcpServer {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.terminal.TcpServer");
    private final @NonNull CommandRegistry registry;
    private final @NonNull AgentSessions runtime;
    private final @NonNull SessionService sessions;
    private final @NonNull SessionCommandService commands;
    private final @NonNull ServerSocketChannel listener;
    private final @NonNull ConcurrentHashMap<@NonNull String, @NonNull ProtocolConnection>
            connections = new ConcurrentHashMap<>();
    private final @NonNull ExecutorService workers =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("terminal-connection-", 0).factory());
    private Thread acceptor;
    private volatile boolean running;

    public TcpServer(
            @NonNull CommandRegistry registry,
            @Value("${veto.terminal.bind-address}") @NonNull String bindAddress,
            @NonNull AgentSessions runtime,
            @NonNull SessionService sessions,
            @NonNull SessionCommandService commands)
            throws IOException {
        this.registry = registry;
        this.runtime = runtime;
        this.sessions = sessions;
        this.commands = commands;
        listener = TcpConnection.bind(bindAddress);
    }

    @PostConstruct
    public synchronized void start() {
        if (acceptor != null) throw new IllegalStateException("Terminal server already started");
        running = true;
        acceptor = Thread.ofVirtual().name("terminal-accept").start(this::acceptLoop);
    }

    @PreDestroy
    public void stop() throws IOException, InterruptedException {
        try {
            synchronized (this) {
                running = false;
                if (listener.isOpen()) listener.close();
            }
        } finally {
            for (var connection : connections.values())
                connection.close(CloseReason.SERVER_SHUTDOWN);
            try {
                Threads.awaitTermination(acceptor, 0);
            } finally {
                workers.close();
            }
        }
    }

    private void acceptLoop() {
        try {
            while (running) {
                var channel = listener.accept();
                workers.execute(() -> serve(channel));
            }
        } catch (Throwable failure) {
            if (running) log.warn("Terminal accept failed", failure);
        }
        running = false;
        for (var connection : connections.values()) connection.close(CloseReason.IO_ERROR);
    }

    private void serve(@NonNull SocketChannel channel) {
        TcpConnection transport;
        try {
            transport = TcpConnection.accept(channel);
        } catch (IOException failure) {
            log.debug("Terminal connection initialization failed", failure);
            return;
        }
        var id = UUID.randomUUID().toString();
        var connection =
                new ProtocolConnection(
                        id,
                        runtime,
                        sessions,
                        commands,
                        registry,
                        null,
                        () -> true,
                        BooleanSupplier::getAsBoolean,
                        workers,
                        transport::send,
                        reason -> {
                            transport.close();
                            if (connections.remove(id) != null)
                                log.debug("TCP: Connection '{}' closed", id);
                        },
                        Locale.getDefault());
        synchronized (this) {
            if (!running) {
                connection.close(CloseReason.SERVER_SHUTDOWN);
                return;
            }
            connections.put(id, connection);
        }
        try {
            while (!connection.isClosed()) {
                var incoming = transport.receive();
                if (!(incoming instanceof Frame.ClientFrame frame))
                    throw new IOException("Expected a client frame");
                connection.receive(frame);
            }
        } catch (Throwable failure) {
            if (!connection.isClosed()) log.debug("Terminal input failed for '{}'", id, failure);
            connection.close(CloseReason.IO_ERROR);
        }
    }

    public void revokeUser(@NonNull UUID userId) {
        for (var connection : connections.values()) connection.revokeUser(userId);
    }
}
