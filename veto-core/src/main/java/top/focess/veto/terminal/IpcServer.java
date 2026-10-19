package top.focess.veto.terminal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import top.focess.veto.api.util.Resources;
import top.focess.veto.api.util.Threads;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.SessionService;
import top.focess.veto.transport.tcp.TcpConnection;

/**
 * TCP terminal server. This Spring component owns its listener and connection tasks. Each {@link
 * IpcConnection} owns its socket, protocol and bounded output queue. Only receiving waits on an
 * idle connection; a serialized output task runs while data is queued. Admission, output and
 * closure are thread-safe.
 */
@Component
@ConditionalOnProperty(name = "veto.terminal.enabled", havingValue = "true")
public final class IpcServer {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.terminal.IpcServer");
    private final @NonNull CommandRegistry registry;
    private final @NonNull AgentSessions runtime;
    private final @NonNull SessionService sessionService;
    private final @NonNull SessionCommandService commands;
    private final @NonNull ServerSocketChannel listener;
    private final @NonNull ConcurrentHashMap<@NonNull String, @NonNull IpcConnection> connections =
            new ConcurrentHashMap<>();
    private final @NonNull ExecutorService workers =
            Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("terminal-io-", 0).factory());
    private Thread acceptor;
    private volatile boolean running;

    public IpcServer(
            @NonNull CommandRegistry registry,
            @Value("${veto.terminal.bind-address}") @NonNull String bindAddress,
            @NonNull AgentSessions runtime,
            @NonNull SessionService sessionService,
            @NonNull SessionCommandService commands)
            throws IOException {
        this.registry = registry;
        this.runtime = runtime;
        this.sessionService = sessionService;
        this.commands = commands;
        listener = TcpConnection.bind(bindAddress);
    }

    @PostConstruct
    public synchronized void start() {
        if (acceptor != null) throw new IllegalStateException("Terminal server already started");
        running = true;
        try {
            acceptor = Thread.ofVirtual().name("terminal-accept").start(this::accept);
        } catch (Throwable failure) {
            Resources.closeOnFailure(listener, failure);
            workers.close();
            throw failure;
        }
    }

    @PreDestroy
    public void stop() throws IOException, InterruptedException {
        try {
            synchronized (this) {
                running = false;
                // Close wakes accept; joins below must not hold the admission monitor.
                if (listener.isOpen()) listener.close();
            }
        } finally {
            try {
                Threads.awaitTermination(acceptor, 0);
            } finally {
                workers.close();
            }
        }
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
        for (var connection : connections.values()) connection.stop(false);
    }

    private void admit(@NonNull SocketChannel channel) {
        TcpConnection transport;
        try {
            transport = TcpConnection.accept(channel);
        } catch (IOException failure) {
            log.debug("Terminal connection initialization failed ({})", failure.toString());
            return;
        }
        IpcConnection connection = null;
        try {
            synchronized (this) {
                if (running) {
                    connection =
                            new IpcConnection(
                                    transport,
                                    workers,
                                    runtime,
                                    sessionService,
                                    commands,
                                    registry,
                                    closed -> {
                                        if (connections.remove(closed.identity, closed))
                                            log.debug(
                                                    "Terminal connection '{}' closed",
                                                    closed.identity);
                                    });
                    connections.put(connection.identity, connection);
                    workers.execute(connection::read);
                } else transport.close();
            }
        } catch (Throwable failure) {
            if (connection == null) transport.close();
            else connection.stop(false);
            throw failure;
        }
    }

    public void revokeUser(@NonNull UUID userId) {
        for (var connection : connections.values()) connection.protocol.revokeUser(userId);
    }
}
