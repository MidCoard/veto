package top.focess.veto.terminal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.zeromq.ZContext;
import top.focess.veto.VetoVersion;
import top.focess.veto.agent.AgentService;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameMeta;
import top.focess.veto.contract.ServerTransport;
import top.focess.veto.transport.zmq.ZmqChannel;
import top.focess.veto.vault.ExecutionSecurity;

/**
 * Terminal endpoint. One IO worker owns the router; each session mailbox owns its request
 * lifecycle. Command workers post results to that mailbox and never change request state
 * themselves.
 */
@Component
@ConditionalOnProperty(name = "veto.terminal.enabled", havingValue = "true", matchIfMissing = true)
public class IpcServer {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.terminal.IpcServer");
    private static final long SESSION_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(90);
    private static final long SESSION_SCAN_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final @NonNull CommandRegistry registry;
    private final @NonNull AgentService agentService;
    private final @NonNull String bindAddress;
    private final @NonNull BlockingQueue<@NonNull Outgoing> outbox =
            new ArrayBlockingQueue<>(10_000);
    private final @NonNull ConcurrentHashMap<@NonNull String, @NonNull Session> sessions =
            new ConcurrentHashMap<>();
    private final @NonNull ExecutorService sessionWorkers =
            Executors.newVirtualThreadPerTaskExecutor();
    private Thread ioThread;
    private volatile boolean running;

    public IpcServer(
            @NonNull CommandRegistry registry,
            @NonNull AgentService agentService,
            @Value("${veto.terminal.bind-address}") @NonNull String bindAddress) {
        this.registry = registry;
        this.agentService = agentService;
        this.bindAddress = bindAddress;
    }

    /** Binds on the IO owner and reports startup failure to Spring. */
    @PostConstruct
    public void start() {
        var ready = new CompletableFuture<Void>();
        running = true;
        ioThread = Thread.ofVirtual().name("terminal-io").start(() -> serve(ready));
        try {
            ready.get();
        } catch (InterruptedException interrupted) {
            stop();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Terminal startup interrupted", interrupted);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Cannot start terminal endpoint", failure.getCause());
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        var worker = ioThread;
        if (worker != null && worker != Thread.currentThread()) {
            try {
                worker.join(4_000);
                if (worker.isAlive()) log.warn("Terminal IO owner is still closing its socket");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void serve(@NonNull CompletableFuture<Void> ready) {
        try (var context = new ZContext();
                var transport = ZmqChannel.Server.bindRouter(context, bindAddress)) {
            ready.complete(null);
            log.info("Terminal endpoint bound to {}", bindAddress);
            long lastScan = System.nanoTime();
            try {
                while (running) {
                    var received = transport.recv(outbox.isEmpty() ? 50 : 0);
                    if (received != null) receive(received.identity(), received.frame());
                    for (int i = 0; running && i < 256; i++) {
                        var outgoing = outbox.poll();
                        if (outgoing == null) break;
                        deliver(transport, outgoing.identity(), outgoing.frame());
                    }
                    long now = System.nanoTime();
                    if (now - lastScan >= SESSION_SCAN_NANOS) {
                        for (var session : sessions.values()) {
                            if (now - session.lastReceived >= SESSION_TIMEOUT_NANOS) {
                                send(session.identity, new Frame.Terminate("Session timed out."));
                                session.post(new Disconnect());
                            }
                        }
                        lastScan = now;
                    }
                }
            } finally {
                running = false;
                outbox.clear();
                long goodbyeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                for (var session : sessions.values()) {
                    // Best-effort notification has one shared deadline, not one timeout per peer.
                    if (System.nanoTime() < goodbyeDeadline)
                        deliver(
                                transport,
                                session.identity,
                                new Frame.Terminate("Server shutting down."));
                    session.post(new Disconnect());
                }
            }
        } catch (RuntimeException | Error failure) {
            if (!ready.completeExceptionally(failure))
                log.warn("Terminal IO failed ({})", failure.getClass().getSimpleName());
        } finally {
            running = false;
            sessionWorkers.shutdownNow();
        }
    }

    private void receive(@NonNull String identity, Frame.@NonNull ClientFrame frame) {
        if (frame instanceof Frame.Hello hello) {
            if (hello.version() != Frame.PROTOCOL_VERSION) {
                send(identity, new Frame.Error("Unsupported protocol version", hello.seq()));
                return;
            }
            var session =
                    new Session(
                            identity,
                            new VetoCommandSender(
                                    this, null, identity, hello.productVersion(), hello.cwd()));
            if (sessions.putIfAbsent(identity, session) != null) {
                send(identity, new Frame.Error("Duplicate identity connected.", hello.seq()));
                return;
            }
            sessionWorkers.execute(session::run);
            send(
                    identity,
                    new Frame.Welcome(Frame.PROTOCOL_VERSION, hello.seq(), VetoVersion.VERSION));
            return;
        }
        var session = sessions.get(identity);
        if (session == null) {
            send(identity, new Frame.Terminate("Session no longer valid — please reconnect."));
        } else {
            session.lastReceived = System.nanoTime();
            session.post(new Incoming(frame));
        }
    }

    private void deliver(
            @NonNull ServerTransport transport,
            @NonNull String identity,
            Frame.@NonNull ServerFrame frame) {
        try {
            transport.send(identity, frame);
        } catch (RuntimeException failure) {
            log.warn("Terminal send failed ({})", failure.getClass().getSimpleName());
            var session = sessions.get(identity);
            if (session != null) session.post(new Disconnect());
        }
    }

    /** Thread-safe output handoff; only the IO owner touches the socket. */
    public void send(@NonNull String identity, Frame.@NonNull ServerFrame frame) {
        if (running && !outbox.offer(new Outgoing(identity, frame))) {
            log.warn("Terminal outbox is full; disconnecting the affected peer");
            var session = sessions.get(identity);
            if (session != null) session.post(new Disconnect());
        }
    }

    /** Authentication revocation is immediate, independently of a command waiting for input. */
    public void revokeUser(@NonNull UUID userId) {
        for (var session : sessions.values()) {
            if (userId.equals(session.sender.userId())) {
                session.sender.setUser(null);
                if (session.sender.cancelCurrentPrompt())
                    log.debug("Cancelled pending input for revoked user {}", userId);
            }
        }
    }

    private record Outgoing(@NonNull String identity, Frame.@NonNull ServerFrame frame) {}

    private sealed interface SessionEvent {}

    private record Incoming(Frame.@NonNull ClientFrame frame) implements SessionEvent {}

    private record Finished(@NonNull Thread request, Frame.@NonNull TerminalResponse response)
            implements SessionEvent {}

    private record Disconnect() implements SessionEvent {}

    private record ActiveRequest(@NonNull Thread worker, boolean cancelled) {}

    /** Only run() and its synchronous helpers access activeRequest and pendingRequests. */
    private final class Session {
        private final @NonNull String identity;
        private final @NonNull VetoCommandSender sender;
        private final @NonNull BlockingQueue<@NonNull SessionEvent> mailbox =
                new LinkedBlockingQueue<>();
        private final @NonNull ArrayDeque<Frame.@NonNull Request> pendingRequests =
                new ArrayDeque<>();
        private volatile long lastReceived = System.nanoTime();
        private ActiveRequest activeRequest;

        private Session(@NonNull String identity, @NonNull VetoCommandSender sender) {
            this.identity = identity;
            this.sender = sender;
        }

        private void post(@NonNull SessionEvent event) {
            if (!mailbox.offer(event))
                throw new IllegalStateException("Cannot enqueue terminal event");
        }

        private void run() {
            try {
                while (true) {
                    var event = mailbox.take();
                    if (event instanceof Disconnect) break;
                    if (event instanceof Finished finished) {
                        var active = activeRequest;
                        if (active != null && active.worker() == finished.request()) {
                            activeRequest = null;
                            send(
                                    identity,
                                    active.cancelled()
                                            ? new Frame.Done(
                                                    Map.of(FrameMeta.CANCELLED, true), null)
                                            : finished.response());
                            startNext();
                        }
                    } else if (event instanceof Incoming incoming) {
                        if (incoming.frame() instanceof Frame.Bye) break;
                        var security = ExecutionSecurity.open(sender.userId());
                        try {
                            handle(incoming.frame());
                        } catch (RuntimeException failure) {
                            log.warn(
                                    "Terminal frame failed ({})",
                                    failure.getClass().getSimpleName());
                            long sequence =
                                    incoming.frame() instanceof Frame.SeqRequest request
                                            ? request.seq()
                                            : 0;
                            send(
                                    identity,
                                    new Frame.Error("Frame could not be processed.", sequence));
                        } finally {
                            security.close();
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                sessions.remove(identity, this);
                var request = activeRequest;
                if (request != null) request.worker().interrupt();
                pendingRequests.clear();
                mailbox.clear();
            }
        }

        private void handle(Frame.@NonNull ClientFrame frame) {
            switch (frame) {
                case Frame.Request request -> {
                    pendingRequests.addLast(request);
                    if (activeRequest == null) startNext();
                }
                case Frame.Input input -> {
                    var veto = sender.claimPendingVeto();
                    if (veto != null)
                        agentService.resolveVeto(veto.agentId(), veto.callId(), input.raw());
                    else if (!sender.receiveInput(input.raw()))
                        log.debug("Discarded input without a pending prompt");
                }
                case Frame.Complete complete ->
                        send(
                                identity,
                                new Frame.CompleteResult(
                                        registry.complete(sender, complete.raw()), complete.seq()));
                case Frame.Hint hint ->
                        send(
                                identity,
                                new Frame.HintResult(
                                        registry.hint(sender, hint.raw()), hint.seq()));
                case Frame.Cancel ignored -> cancel();
                case Frame.Heartbeat heartbeat ->
                        send(identity, new Frame.HeartbeatAck(heartbeat.seq(), Instant.now()));
                default ->
                        send(identity, new Frame.Error("Unsupported frame on this connection", 0));
            }
        }

        private void cancel() {
            var request = activeRequest;
            if (request == null) {
                send(identity, Frame.Error.ofError("No in-flight request to cancel."));
            } else if (request.cancelled()) {
                // The existing worker still owns its output until it really exits.
                return;
            } else if (sender.cancelCurrentPrompt()) {
                log.debug("Dismissed terminal prompt");
            } else if (sender.claimPendingVeto() instanceof VetoCommandSender.PendingVeto veto) {
                agentService.declineVeto(veto.agentId(), veto.callId());
            } else {
                activeRequest = new ActiveRequest(request.worker(), true);
                request.worker().interrupt();
            }
        }

        private void startNext() {
            var request = pendingRequests.pollFirst();
            if (request == null) return;
            var worker =
                    Thread.ofVirtual().name("terminal-request").unstarted(() -> execute(request));
            activeRequest = new ActiveRequest(worker, false);
            worker.start();
        }

        private void execute(Frame.@NonNull Request request) {
            Frame.TerminalResponse response;
            // Resolve when execution starts: logout must revoke requests queued behind earlier
            // work.
            var security = ExecutionSecurity.open(sender.userId());
            try {
                response = registry.dispatch(sender, request.raw());
            } catch (Throwable failure) {
                log.error("Terminal command failed", failure);
                response = Frame.Error.ofError("Command failed.");
            } finally {
                security.close();
            }
            post(new Finished(Thread.currentThread(), response));
        }
    }
}
