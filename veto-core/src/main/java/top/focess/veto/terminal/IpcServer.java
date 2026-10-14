package top.focess.veto.terminal;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.ServerTransport;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.ProtocolConnection;
import top.focess.veto.session.SessionService;

/**
 * IPC transport owner: one virtual IO worker owns the ROUTER and bounded outbox. Shared
 * ProtocolConnection instances own application exchanges. Spring owns transport/context closure.
 */
public final class IpcServer {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.terminal.IpcServer");
    private static final long SESSION_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(90);
    private static final long SESSION_SCAN_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final @NonNull CommandRegistry registry;
    private final @NonNull AgentSessions runtime;
    private final @NonNull SessionService sessionService;
    private final @NonNull SessionCommandService commands;
    private final @NonNull ServerTransport transport;
    private final @NonNull BlockingQueue<@NonNull Outgoing> outbox =
            new ArrayBlockingQueue<>(10_000);
    private final @NonNull ConcurrentHashMap<@NonNull String, @NonNull Peer> peers =
            new ConcurrentHashMap<>();
    private Thread ioThread;
    private volatile boolean running;

    public IpcServer(
            @NonNull CommandRegistry registry,
            @NonNull ServerTransport transport,
            @NonNull AgentSessions runtime,
            @NonNull SessionService sessionService,
            @NonNull SessionCommandService commands) {
        this.registry = registry;
        this.transport = transport;
        this.runtime = runtime;
        this.sessionService = sessionService;
        this.commands = commands;
    }

    public void start() {
        running = true;
        ioThread = Thread.ofVirtual().name("terminal-io").start(this::serve);
    }

    /** Spring cannot close borrowed resources until the exclusive IO owner has exited. */
    public void stop() {
        running = false;
        var worker = ioThread;
        if (worker == null || worker == Thread.currentThread()) return;
        boolean interrupted = false;
        while (worker.isAlive()) {
            try {
                worker.join();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void serve() {
        try {
            long lastScan = System.nanoTime();
            while (running) {
                var received = transport.recv(outbox.isEmpty() ? 50 : 0);
                if (received != null) receive(received.identity(), received.frame());
                for (int i = 0; running && i < 256; i++) {
                    var outgoing = outbox.poll();
                    if (outgoing == null) break;
                    deliver(outgoing);
                }
                long now = System.nanoTime();
                if (now - lastScan >= SESSION_SCAN_NANOS) {
                    for (var peer : peers.values()) {
                        if (now - peer.lastReceived >= SESSION_TIMEOUT_NANOS) {
                            deliver(
                                    new Outgoing(
                                            peer.identity,
                                            peer,
                                            peer.connection.authenticationVersion(),
                                            new Frame.Terminate("Session timed out."),
                                            () -> true));
                            peer.connection.close();
                        }
                    }
                    lastScan = now;
                }
            }
        } catch (Throwable failure) {
            log.warn("Terminal IO failed", failure);
        } finally {
            running = false;
            outbox.clear();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            for (var peer : peers.values()) {
                if (System.nanoTime() < deadline)
                    deliver(
                            new Outgoing(
                                    peer.identity,
                                    peer,
                                    peer.connection.authenticationVersion(),
                                    new Frame.Terminate("Server shutting down."),
                                    () -> true));
                peer.connection.close();
            }
        }
    }

    private void receive(@NonNull String identity, Frame.@NonNull ClientFrame frame) {
        var peer = peers.get(identity);
        if (peer == null) {
            if (!(frame instanceof Frame.Hello)) {
                enqueue(
                        new Outgoing(
                                identity,
                                null,
                                0,
                                new Frame.Terminate("Session no longer valid — please reconnect."),
                                () -> true));
                return;
            }
            peer = createPeer(identity);
            peers.put(identity, peer);
        }
        peer.lastReceived = System.nanoTime();
        peer.connection.receive(frame);
    }

    private void deliver(@NonNull Outgoing outgoing) {
        var peer = outgoing.owner();
        if (peers.get(outgoing.identity()) != peer) return;
        var frame =
                peer == null
                        ? outgoing.frame()
                        : peer.connection.authorizedOutput(
                                outgoing.frame(), outgoing.version(), outgoing.attached());
        if (frame == null) return;
        try {
            transport.send(outgoing.identity(), frame);
            if (peer != null && frame instanceof Frame.Terminate) {
                peers.remove(peer.identity, peer);
                peer.connection.close();
            }
        } catch (RuntimeException failure) {
            log.warn("Terminal send failed ({})", failure.getClass().getSimpleName());
            if (peer != null) peer.connection.close();
        }
    }

    private boolean enqueue(@NonNull Outgoing outgoing) {
        if (!running) return false;
        if (outbox.offer(outgoing)) return true;
        log.warn("Terminal outbox is full; disconnecting the affected peer");
        var peer = outgoing.owner();
        if (peer != null) peer.connection.close();
        return false;
    }

    public void revokeUser(@NonNull UUID userId) {
        for (var peer : peers.values()) {
            peer.connection.revokeUser(userId);
        }
    }

    private record Outgoing(
            @NonNull String identity,
            Peer owner,
            long version,
            Frame.@NonNull ServerFrame frame,
            @NonNull BooleanSupplier attached) {}

    /** Bind callbacks only to the completed owner before publishing it in the peer registry. */
    private @NonNull Peer createPeer(@NonNull String identity) {
        var owner = new AtomicReference<Peer>();
        var connection =
                new ProtocolConnection(
                        identity,
                        runtime,
                        sessionService,
                        commands,
                        registry,
                        null,
                        () -> true,
                        BooleanSupplier::getAsBoolean,
                        (frame, version, attached) ->
                                enqueue(
                                        new Outgoing(
                                                identity,
                                                Objects.requireNonNull(
                                                        owner.get(), "Peer not initialized"),
                                                version,
                                                frame,
                                                attached)),
                        drain ->
                                Objects.requireNonNull(owner.get(), "Peer not initialized")
                                        .closed(drain),
                        Locale.getDefault());
        var peer = new Peer(identity, connection);
        owner.set(peer);
        return peer;
    }

    private final class Peer {
        final @NonNull String identity;
        final @NonNull ProtocolConnection connection;
        volatile long lastReceived = System.nanoTime();

        Peer(@NonNull String identity, @NonNull ProtocolConnection connection) {
            this.identity = identity;
            this.connection = connection;
        }

        private void closed(boolean drain) {
            if (drain && running)
                enqueue(
                        new Outgoing(
                                identity,
                                this,
                                connection.authenticationVersion(),
                                new Frame.Terminate("Connection closed."),
                                () -> true));
            else peers.remove(identity, this);
        }
    }
}
