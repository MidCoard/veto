package top.focess.veto.terminal;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.VetoVersion;
import top.focess.veto.agent.AgentService;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.ServerTransport;
import top.focess.veto.vault.ExecutionSecurity;

/**
 * Terminal endpoint. One IO worker owns the router; each session mailbox owns its request
 * lifecycle. Command workers post results to that mailbox and never change request state
 * themselves.
 */
public class IpcServer {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.terminal.IpcServer");
    private static final long SESSION_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(90);
    private static final long SESSION_SCAN_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final @NonNull CommandRegistry registry;
    private final @NonNull AgentService agentService;
    private final @NonNull ServerTransport transport;
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
            @NonNull ServerTransport transport) {
        this.registry = registry;
        this.agentService = agentService;
        this.transport = transport;
    }

    /** Starts the sole IO worker after Spring has initialized its resources. */
    public void start() {
        running = true;
        ioThread = Thread.ofVirtual().name("terminal-io").start(this::serve);
    }

    /** Returns only after the worker releases the transport for Spring to close. */
    public void stop() {
        running = false;
        var worker = ioThread;
        if (worker == null || worker == Thread.currentThread()) return;
        boolean interrupted = false;
        while (worker.isAlive()) {
            try {
                worker.join();
            } catch (InterruptedException ignored) {
                // Resource destruction must not race the IO worker, even on interrupted shutdown.
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void serve() {
        try {
            long lastScan = System.nanoTime();
            try {
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
                                new Outgoing(
                                        session.identity,
                                        session,
                                        session.sender.authenticationVersion(),
                                        new Frame.Terminate("Server shutting down.")));
                    session.post(new Disconnect());
                }
            }
        } catch (Throwable failure) {
            log.warn("Terminal IO failed", failure);
        } finally {
            running = false;
            sessionWorkers.shutdownNow();
        }
    }

    /** Plugin events are independent of an active command, but remain session-authorized. */
    void publishAction(Frame.@NonNull ScopedFrame frame) {
        var scope =
                switch (frame) {
                    case Frame.PluginAction action -> action.scope();
                    case Frame.Action action -> action.scope();
                    default -> null;
                };
        if (scope == null) return;
        var event = (Frame.ServerFrame) frame;
        for (var session : sessions.values()) {
            if (scope.userId().equals(session.sender.userId())
                    && scope.session().equals(registry.activeSession(session.identity)))
                send(session.sender, event);
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

    private void deliver(@NonNull Outgoing outgoing) {
        var owner = outgoing.owner();
        if (sessions.get(outgoing.identity()) != owner) return;
        if (owner != null) {
            // Identity changes and socket admission share the sender's ownership boundary.
            synchronized (owner.sender) {
                if (sessions.get(outgoing.identity()) != owner) return;
                if (outgoing.authenticationVersion() != owner.sender.authenticationVersion()) {
                    if (outgoing.frame() instanceof Frame.Done
                            || outgoing.frame() instanceof Frame.Error error && error.seq() == 0) {
                        // Completion belongs to the original command even after its authority ends.
                        outgoing =
                                new Outgoing(
                                        outgoing.identity(),
                                        owner,
                                        owner.sender.authenticationVersion(),
                                        registry.terminalOutcome(
                                                owner.sender,
                                                new Frame.Done(
                                                        new Scope.GlobalScope(),
                                                        null,
                                                        -1,
                                                        null,
                                                        null)));
                    } else if (!(outgoing.frame() instanceof Frame.HeartbeatAck)
                            && !(outgoing.frame() instanceof Frame.CancelResult)
                            && !(outgoing.frame() instanceof Frame.Terminate)) return;
                }
                if (!authorizedOutput(owner.sender, outgoing.frame())) return;
                deliverFrame(transport, outgoing);
            }
        } else deliverFrame(transport, outgoing);
    }

    private static boolean authorizedOutput(
            @NonNull VetoCommandSender sender, Frame.@NonNull ServerFrame frame) {
        if (!(frame instanceof Frame.ScopedFrame scoped)) return true;
        var user = scoped.scope().userId();
        return user == null || user.equals(sender.userId());
    }

    private void deliverFrame(@NonNull ServerTransport transport, @NonNull Outgoing outgoing) {
        try {
            transport.send(outgoing.identity(), outgoing.frame());
        } catch (RuntimeException failure) {
            log.warn("Terminal send failed ({})", failure.getClass().getSimpleName());
            var session = outgoing.owner();
            if (session != null) session.post(new Disconnect());
        }
    }

    /** Thread-safe output handoff; only the IO owner touches the socket. */
    private void send(@NonNull String identity, Frame.@NonNull ServerFrame frame) {
        var owner = sessions.get(identity);
        if (owner != null) send(owner.sender, frame);
        else enqueue(new Outgoing(identity, null, -1, frame));
    }

    /** Output remains bound to the producing connection even if its routing identity is reused. */
    public void send(@NonNull VetoCommandSender sender, Frame.@NonNull ServerFrame frame) {
        synchronized (sender) {
            send(sender, sender.outputAuthenticationVersion(), frame);
        }
    }

    private void send(
            @NonNull VetoCommandSender sender,
            long authenticationVersion,
            Frame.@NonNull ServerFrame frame) {
        synchronized (sender) {
            var owner = sessions.get(sender.terminalId());
            if (owner == null || owner.sender != sender) return;
            boolean authorized =
                    authenticationVersion == sender.authenticationVersion()
                            && authorizedOutput(sender, frame);
            if (frame instanceof Frame.TurnOutcome outcome) {
                if (!authorized && outcome instanceof Frame.Error error && error.seq() != 0) return;
                frame =
                        registry.terminalOutcome(
                                sender,
                                authorized
                                        ? outcome
                                        : new Frame.Done(
                                                new Scope.GlobalScope(), null, -1, null, null));
            } else if (!authorized
                    && !(frame instanceof Frame.HeartbeatAck)
                    && !(frame instanceof Frame.CancelResult)
                    && !(frame instanceof Frame.Terminate)) return;
            enqueue(new Outgoing(owner.identity, owner, sender.authenticationVersion(), frame));
        }
    }

    private void enqueue(@NonNull Outgoing outgoing) {
        if (running && !outbox.offer(outgoing)) {
            log.warn("Terminal outbox is full; disconnecting the affected peer");
            var session = outgoing.owner();
            if (session != null) session.post(new Disconnect());
        }
    }

    /** Authentication revocation is immediate, independently of a command waiting for input. */
    public void revokeUser(@NonNull UUID userId) {
        for (var session : sessions.values()) {
            synchronized (session.sender) {
                if (userId.equals(session.sender.userId())) {
                    session.sender.setUser(null);
                    if (session.sender.cancelCurrentPrompt())
                        log.debug("Cancelled pending input for revoked user {}", userId);
                }
            }
        }
    }

    private record Outgoing(
            @NonNull String identity,
            Session owner,
            long authenticationVersion,
            Frame.@NonNull ServerFrame frame) {}

    private sealed interface SessionEvent {}

    private record Incoming(Frame.@NonNull ClientFrame frame) implements SessionEvent {}

    private record Finished(
            @NonNull Thread request,
            Frame.@NonNull ServerFrame response,
            long authenticationVersion)
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
                                    sender,
                                    finished.authenticationVersion(),
                                    active.cancelled()
                                            ? new Frame.Done(
                                                    new Scope.GlobalScope(), null, -1, null, null)
                                            : finished.response());
                            startNext();
                        }
                    } else if (event instanceof Incoming incoming) {
                        if (incoming.frame() instanceof Frame.Bye) break;
                        UUID owner;
                        synchronized (sender) {
                            sender.beginExecution();
                            owner = sender.userId();
                        }
                        var security = ExecutionSecurity.open(owner);
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
                                    sender,
                                    new Frame.Error("Frame could not be processed.", sequence));
                        } finally {
                            sender.endExecution();
                            security.close();
                        }
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                synchronized (sender) {
                    sessions.remove(identity, this);
                }
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
                    if (!sender.receiveInput(input.raw()))
                        log.debug("Discarded input without a pending prompt");
                }
                case Frame.VetoResult result -> {
                    synchronized (sender) {
                        if (sender.claimPendingVeto(result))
                            agentService.resolveVeto(
                                    result.scope().agent(),
                                    result.callId(),
                                    result.option(),
                                    result.seq());
                        else log.debug("Discarded decision without a matching pending approval");
                    }
                }
                case Frame.Complete complete ->
                        send(
                                sender,
                                new Frame.CompleteResult(
                                        registry.complete(sender, complete.raw()), complete.seq()));
                case Frame.Hint hint ->
                        send(
                                sender,
                                new Frame.HintResult(
                                        registry.hint(sender, hint.raw()), hint.seq()));
                case Frame.Cancel request ->
                        send(sender, new Frame.CancelResult(cancel(), request.seq()));
                case Frame.Heartbeat ignored -> send(sender, new Frame.HeartbeatAck(Instant.now()));
                default -> send(sender, new Frame.Error("Unsupported frame on this connection", 0));
            }
        }

        private Frame.@NonNull CancelStatus cancel() {
            try {
                if (sender.cancelCurrentPrompt()) {
                    log.debug("Dismissed terminal prompt");
                    return Frame.CancelStatus.ACCEPTED;
                }
                if (sender.claimPendingVeto() instanceof VetoCommandSender.PendingVeto veto) {
                    return agentService.declineVeto(veto.scope().agent(), veto.callId())
                            ? Frame.CancelStatus.ACCEPTED
                            : Frame.CancelStatus.REJECTED;
                }
                var request = activeRequest;
                if (request == null) return Frame.CancelStatus.NO_ACTIVE_REQUEST;
                // Repeated cancellation does not interrupt the worker a second time.
                if (request.cancelled()) return Frame.CancelStatus.ACCEPTED;
                request.worker().interrupt();
                activeRequest = new ActiveRequest(request.worker(), true);
                return Frame.CancelStatus.ACCEPTED;
            } catch (RuntimeException failure) {
                log.warn("Terminal cancellation failed ({})", failure.getClass().getSimpleName());
                return Frame.CancelStatus.REJECTED;
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
            Frame.ServerFrame response;
            long authenticationVersion;
            // Resolve when execution starts: logout must revoke requests queued behind earlier
            // work.
            UUID owner;
            synchronized (sender) {
                sender.beginExecution();
                owner = sender.userId();
            }
            var security = ExecutionSecurity.open(owner);
            try {
                response = registry.dispatch(sender, request.raw());
            } catch (Throwable failure) {
                log.error("Terminal command failed", failure);
                response = Frame.Error.ofError("Command failed.");
            } finally {
                authenticationVersion = sender.outputAuthenticationVersion();
                sender.endExecution();
                security.close();
            }
            post(new Finished(Thread.currentThread(), response, authenticationVersion));
        }
    }
}
