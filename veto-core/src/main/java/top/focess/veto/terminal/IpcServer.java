package top.focess.veto.terminal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.concurrent.DelegatingSecurityContextCallable;
import org.springframework.stereotype.Component;
import org.zeromq.ZContext;
import top.focess.veto.VetoVersion;
import top.focess.veto.agent.AgentService;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.Frame.HintInfo;
import top.focess.veto.contract.FrameMeta;
import top.focess.veto.contract.ProtocolClient;
import top.focess.veto.contract.ServerTransport;
import top.focess.veto.contract.Version;
import top.focess.veto.transport.zmq.ZmqChannel;
import top.focess.veto.vault.ExecutionSecurity;

/**
 * Terminal entry point: one virtual IO owner binds the ZeroMQ ROUTER, routes frames, scans idle
 * sessions and closes the socket. Per-session workers keep ordered input/cancel/completion handling
 * responsive while commands run separately. Request locking preserves one terminal reply per request.
 */
@Component
@ConditionalOnProperty(name = "veto.terminal.enabled", havingValue = "true", matchIfMissing = true)
public class IpcServer {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.terminal.IpcServer");

    private static final long SESSION_TIMEOUT_MS = 90_000;

    /** Check for stale sessions 3× per timeout window to bound the worst-case eviction lag. */
    private static final long HEARTBEAT_CHECK_MS = SESSION_TIMEOUT_MS / 3;

    private static final int MAX_OUTBOX_SIZE = 10_000;

    private final @NonNull CommandRegistry registry;
    private final @NonNull AgentService agentService;

    /** Many producers enqueue; only the IO owner sends. Capacity is enforced by the queue itself. */
    private final @NonNull BlockingQueue<@NonNull OutboxEntry> outbox =
            new ArrayBlockingQueue<>(MAX_OUTBOX_SIZE);

    /** Active sessions keyed by ZMQ identity string. */
    private final @NonNull ConcurrentHashMap<@NonNull String, @NonNull Session> sessions =
            new ConcurrentHashMap<>();

    /**
     * Pool 2 — one virtual thread per session. Each session worker blocks on its mailbox queue;
     * virtual threads are ideal here since they park cheaply while waiting for frames.
     */
    private final @NonNull ExecutorService sessionPool =
            Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Pool 3 — one virtual thread per Request task. Commands may block on I/O (AI streaming, DB
     * calls, etc.) for seconds to minutes; virtual threads scale well for this workload.
     */
    private final @NonNull ExecutorService requestPool =
            Executors.newVirtualThreadPerTaskExecutor();

    private Thread ioThread;
    private volatile boolean running;

    private final @NonNull String bindAddress;

    /**
     * Constructs a new {@code IpcServer}. Spring calls this constructor with the {@link
     * CommandRegistry} and {@link AgentService} beans wired from the application context.
     *
     * @param registry the command registry used to dispatch requests and produce completions
     * @param agentService the agent service used to resolve/decline pending HITL vetoes
     * @param bindAddress the required configured ZeroMQ bind address
     */
    public IpcServer(
            @NonNull CommandRegistry registry,
            @NonNull AgentService agentService,
            @Value("${veto.terminal.bind-address}") @NonNull String bindAddress) {
        this.registry = registry;
        this.agentService = agentService;
        this.bindAddress = bindAddress;
    }

    /** Starts the actual terminal endpoint and reports bind failures before Spring admits traffic. */
    @PostConstruct
    public void start() {
        var ready = new CompletableFuture<Void>();
        running = true;
        ioThread = Thread.ofVirtual().name("terminal-io").start(() -> ioLoop(ready));
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

    /** Signals the IO owner, which terminates peers and closes its own transport and context. */
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

    private void ioLoop(@NonNull CompletableFuture<Void> ready) {
        try (var context = new ZContext();
                var transport = ZmqChannel.Server.bindRouter(context, bindAddress)) {
            ready.complete(null);
            log.info("IpcServer bound to {}", bindAddress);
            long lastScan = System.nanoTime();
            try {
                while (running) {
                    var message = transport.recv(outbox.isEmpty() ? 50 : 0);
                    if (message != null) {
                        try {
                            routeFrame(message.identity(), message.frame());
                        } catch (RuntimeException invalid) {
                            rejectFrame(message.identity(), message.frame(), invalid);
                        }
                    }
                    // Bound each drain so producers cannot starve receive, eviction or shutdown.
                    for (int i = 0; running && i < 256; i++) {
                        var entry = outbox.poll();
                        if (entry == null) break;
                        deliver(transport, entry.identity(), entry.frame());
                    }
                    if (System.nanoTime() - lastScan >= TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_CHECK_MS)) {
                        evictIdleSessions();
                        lastScan = System.nanoTime();
                    }
                }
            } finally {
                running = false;
                outbox.clear();
                for (var session : sessions.values()) {
                    deliver(transport, session.identity, new Frame.Terminate("Server shutting down."));
                    closeSession(session);
                }
            }
        } catch (RuntimeException | Error failure) {
            if (!ready.completeExceptionally(failure))
                log.warn("Terminal IO failed ({})", failure.getClass().getSimpleName());
        } finally {
            running = false;
            for (var session : sessions.values()) closeSession(session);
            sessionPool.shutdownNow();
            requestPool.shutdownNow();
        }
    }

    private void deliver(@NonNull ServerTransport transport, @NonNull String identity,
            Frame.@NonNull ServerFrame frame) {
        try {
            transport.send(identity, frame);
        } catch (RuntimeException failure) {
            log.warn("Failed to send {} to {} ({})", frame.getClass().getSimpleName(),
                    peerLabel(identity), failure.getClass().getSimpleName());
        }
    }

    /**
     * Routes a frame that just arrived from the ZMQ socket.
     *
     * <p>{@link Frame.Hello} is handled synchronously here on the IO thread: the session does not
     * exist yet, so there is no mailbox to enqueue into. Every other frame is enqueued to the
     * session's mailbox for ordered processing by the session worker.
     *
     * <p>Must only be called from the IO thread.
     */
    private void routeFrame(@NonNull String identity, @NonNull Frame frame) {
        if (frame instanceof Frame.Hello hello) {
            // Hello is a special bootstrapping frame — handle inline before the session exists.
            handleHello(identity, hello);
            return;
        }

        Session session = sessions.get(identity);
        if (session == null || session.closed.get()) {
            // The peer is sending on a session we don't know (the server restarted, dropping all
            // in-memory sessions) or one already closed. Silently ignoring leaves the terminal
            // hung forever — it keeps heartbeating a dead session with no feedback. Send a
            // Terminate so the terminal's onTerminate surfaces the reason and exits cleanly; the
            // terminal is connect-once by design, so the user re-runs it to reconnect. Bounded:
            // the terminal stops sending once it processes the Terminate.
            log.warn(
                    "Received {} from unknown or closed session {} — terminating stale peer",
                    frame.getClass().getSimpleName(),
                    peerLabel(identity));
            send(
                    identity,
                    new Frame.Terminate(
                            "Session no longer valid (server restarted?) — please reconnect."));
            return;
        }
        // Enqueue to the session mailbox; the session worker consumes frames in order.
        // LinkedBlockingQueue is unbounded by default, so offer should never fail — but
        // guard against it rather than silently dropping a frame.
        if (!session.mailbox.offer(frame)) {
            log.warn(
                    "Mailbox full for session {} — dropping {}",
                    peerLabel(identity),
                    frame.getClass().getSimpleName());
        }
    }

    private static @NonNull String peerLabel(@NonNull String identity) {
        return identity.substring(0, Math.min(8, identity.length()));
    }

    private void rejectFrame(
            @NonNull String identity, @NonNull Frame frame, @NonNull RuntimeException failure) {
        log.warn(
                "Rejected {} frame from {}",
                frame.getClass().getSimpleName(),
                peerLabel(identity),
                failure);
        long seq = frame instanceof Frame.SeqRequest request ? request.seq() : 0;
        send(identity, new Frame.Error("Frame could not be processed.", seq));
    }

    /**
     * Handles a {@link Frame.Hello} handshake directly on the IO thread.
     *
     * <p>Rejects the connection if an active session already exists for the given identity.
     * Otherwise creates the session, starts its worker virtual thread, and sends {@link
     * Frame.Welcome} back.
     */
    private void handleHello(@NonNull String identity, Frame.@NonNull Hello hello) {
        if (hello.version() != Frame.PROTOCOL_VERSION) {
            send(identity, new Frame.Error("Unsupported protocol version", hello.seq()));
            return;
        }
        if (sessions.containsKey(identity)) {
            // The IO thread is the only writer to `sessions`, so containsKey + put is safe here.
            log.warn("Duplicate identity {} — rejecting handshake", peerLabel(identity));
            send(identity, new Frame.Error("Duplicate identity connected.", hello.seq()));
            return;
        }
        Version clientProductVersion = hello.productVersion();
        Session session =
                new Session(identity, createSender(identity, clientProductVersion, hello.cwd()));
        sessions.put(identity, session);
        // Spawn the session worker — virtual thread parks on mailbox.take between frames.
        sessionPool.submit(() -> sessionLoop(session));

        int negotiated = Frame.PROTOCOL_VERSION;
        log.debug(
                "HELLO {}: v{} → negotiated v{} (client product {})",
                peerLabel(identity),
                hello.version(),
                negotiated,
                clientProductVersion);
        send(identity, new Frame.Welcome(negotiated, hello.seq(), VetoVersion.VERSION));
    }

    // ── Pool 2 — Session worker loop ─────────────────────────────────────

    /**
     * The per-session event loop. Runs on a virtual thread from {@link #sessionPool}.
     *
     * <p>Blocks on {@link Session#mailbox} and processes each frame sequentially. This guarantees
     * per-session ordering with no synchronization overhead — only one thread ever processes a
     * given session's frames at a time. {@link Frame.Request} frames are the single exception: they
     * are submitted to {@link #requestPool} so long-running commands never stall this loop.
     */
    private void sessionLoop(@NonNull Session session) {
        log.debug("Session worker started for {}", peerLabel(session.identity));
        while (!session.closed.get()) {
            Frame frame;
            try {
                // Poll with a 1-second timeout so we re-check closure periodically.
                frame = session.mailbox.poll(1, TimeUnit.SECONDS);
                if (frame == null) continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            session.lastActivityNanos = System.nanoTime();
            try {
                handleSessionFrame(session, frame);
            } catch (RuntimeException invalid) {
                rejectFrame(session.identity, frame, invalid);
            }
        }
        // Ensure the session is cleaned up when the loop exits (e.g. server shutdown
        // without an explicit Bye). closeSession is idempotent — if it was already called
        // (Bye, heartbeat timeout), the CAS on `closed` makes this a no-op.
        closeSession(session);
        log.debug("Session worker stopped for {}", peerLabel(session.identity));
    }

    /**
     * Dispatches a single frame on the session worker thread.
     *
     * <p>All frames except {@link Frame.Request} are handled inline — they are fast, stateful
     * operations that must run in order relative to each other (e.g. {@link Frame.Cancel} must see
     * the futures that were registered by previous {@link Frame.Request} dispatches). {@link
     * Frame.Request} is the only frame type that may block for a significant duration and is
     * therefore off-loaded to {@link #requestPool}.
     */
    @SuppressWarnings(
            "LoggingSimilarMessage") // Request/result trace pairs intentionally share a prefix.
    private void handleSessionFrame(@NonNull Session session, @NonNull Frame frame) {
        String identity = session.identity;
        UUID user = session.sender.userId();
        var security = ExecutionSecurity.open(user);
        try {
            switch (frame) {
                case Frame.Request req -> {
                    // 1:1 dispatch: if no request is in-flight, dispatch; otherwise queue.
                    session.requestLock.lock();
                    try {
                        if (session.activeRequest != null) {
                            session.pendingRequests.addLast(req);
                            log.trace(
                                    "REQ  {}: queued (in-flight request already running)",
                                    peerLabel(identity));
                        } else {
                            dispatchRequestLocked(session, req);
                        }
                    } finally {
                        session.requestLock.unlock();
                    }
                }

                case Frame.Input in -> {
                    log.trace("IN   {}", peerLabel(identity));
                    // Veto-first routing: a pending HITL veto consumes this Input as the
                    // chosen option name; only free-text inputs reach receiveInput. The 1:1
                    // invariant (veto-pending XOR free-text-prompt-pending) makes the
                    // single-slot claim safe - the Input replies to whichever Prompt is
                    // outstanding, and exactly one is outstanding at a time.
                    VetoCommandSender.PendingVeto pv = session.sender.claimPendingVeto();
                    if (pv != null) {
                        agentService.resolveVeto(pv.agentId(), pv.callId(), in.raw());
                        log.trace(
                                "IN   {}: resolved veto {} with option '{}'",
                                peerLabel(identity),
                                pv.callId(),
                                in.raw());
                    } else {
                        boolean accepted = session.sender.receiveInput(in.raw());
                        if (!accepted) {
                            // ACTIVE × Input with no waiting request: discard + log. No Error
                            // frame — the terminal only sends Input in reply to a Prompt (client
                            // routing), so an unaccepted Input means no command awaits input; an
                            // Error would break the exactly-one-terminal-frame invariant for an
                            // unrelated Request.
                            log.trace(
                                    "Input from {} with no waiting request — discarding",
                                    peerLabel(identity));
                        }
                    }
                }

                case Frame.Complete comp -> {
                    log.trace("COMP {}: {}", peerLabel(identity), comp.raw());
                    var completions = registry.complete(session.sender, comp.raw());
                    log.trace("COMP {}: → {} candidates", peerLabel(identity), completions.size());
                    send(identity, new Frame.CompleteResult(completions, comp.seq()));
                }

                case Frame.Hint h -> {
                    log.trace("HINT {}: {}", peerLabel(identity), h.raw());
                    HintInfo hint = registry.hint(session.sender, h.raw());
                    log.trace(
                            "HINT {}: → {}",
                            peerLabel(identity),
                            hint == HintInfo.EMPTY ? "EMPTY" : hint.displayText());
                    send(identity, new Frame.HintResult(hint, h.seq()));
                }

                case Frame.Cancel c -> {
                    // Two-level cancel:
                    //   1. If a prompt is pending — cancelCurrentPrompt() dismisses it (returns
                    //      true). The command continues; no terminal frame is sent.
                    //   2. If no prompt is pending — claim the terminal flag, send Done{cancelled},
                    //      and cancel(true) the in-flight task. cancel(true) interrupts the body
                    //      thread and fires done(), which releases the slot and dispatches the next
                    //      queued request — so the user's next command is not blocked behind this
                    //      one. The body unwinds promptly if blocked in interruptible I/O,
                    // otherwise
                    //      winds down on its own; any late sendTerminal is suppressed by the
                    //      identity guard (the slot no longer belongs to it). If the flag was
                    //      already set, the body already sent its terminal frame (cancel raced a
                    //      normal completion) — send nothing and don't cancel.
                    // If no request is in-flight at all, the terminal believes one is running (it's
                    // in RUNNING state) and is blocked awaiting a terminal frame — send an Error to
                    // unblock it so it doesn't hang.
                    session.requestLock.lock();
                    try {
                        if (session.activeRequest != null) {
                            if (session.sender.cancelCurrentPrompt()) {
                                // Level 1: a prompt was pending and has been dismissed.
                                log.trace("CANC {}: dismissed current prompt", peerLabel(identity));
                            } else if (session.sender.claimPendingVeto()
                                    instanceof VetoCommandSender.PendingVeto pv) {
                                // Level 1.5: a HITL veto was pending. cancelCurrentPrompt
                                // returns false for a veto Prompt - the agent parks on the
                                // HitlRegistry, not on an input future. Decline it (fail-safe
                                // refusal); the agent continues, processes the refusal, and sends
                                // its own Done. We do not cancel(true) the task and do not send
                                // Done{cancelled} - the agent's terminal frame still owns the slot.
                                agentService.declineVeto(pv.agentId(), pv.callId());
                                log.trace(
                                        "CANC {}: declined veto {}",
                                        peerLabel(identity),
                                        pv.callId());
                            } else if (!session.terminalSent) {
                                // Level 2: claim the single terminal frame, then cancel(true) the
                                // in-flight task — done() releases the slot and dispatches the next
                                // queued request, and the body thread is interrupted (it unwinds at
                                // its I/O wait point if interruptible). The user's next command is
                                // not blocked behind this one. Any late sendTerminal (if the body
                                // reaches it before unwinding) is suppressed by the identity guard
                                // (the slot no longer belongs to it), so exactly-one still holds.
                                Future<?> task = session.activeRequest;
                                session.terminalSent = true;
                                send(
                                        identity,
                                        new Frame.Done(Map.of(FrameMeta.CANCELLED, true), null));
                                if (task != null) {
                                    task.cancel(true);
                                }
                                log.trace(
                                        "CANC {}: cancelled in-flight request",
                                        peerLabel(identity));
                            }
                            // else: the body already sent its terminal frame — send nothing.
                        } else {
                            // No in-flight request — the terminal thinks one is running and is
                            // blocked awaiting a terminal frame. Send an Error to unblock it.
                            send(identity, Frame.Error.ofError("No in-flight request to cancel."));
                            log.trace(
                                    "CANC {}: no in-flight request — sent error",
                                    peerLabel(identity));
                        }
                    } finally {
                        session.requestLock.unlock();
                    }
                }

                case Frame.Bye b -> {
                    log.trace("BYE  {}: terminal disconnecting", peerLabel(identity));
                    // Bye is fire-and-forget — the client tears down without waiting, and the
                    // server closes on receipt without sending anything back (no Done). Closing
                    // is idempotent; closeSession sets closed=true so the session loop exits.
                    closeSession(session);
                }

                case Frame.Heartbeat h -> {
                    session.lastActivityNanos = System.nanoTime();
                    send(identity, new Frame.HeartbeatAck(h.seq(), Instant.now()));
                }

                default ->
                        send(identity, new Frame.Error("Unsupported frame on this connection", 0));
            }
        } finally {
            security.close();
        }
    }

    // ── Request dispatch ──────────────────────────────────────────────────

    /**
     * Dispatches a {@link Frame.Request} to the request pool and wires up the completion hook.
     *
     * <p>Caller must hold {@link Session#requestLock}. Builds a {@link FutureTask} that runs {@link
     * CommandRegistry#dispatch}, stores it in {@code session.activeRequest}, resets {@link
     * Session#terminalSent}, then submits it to {@link #requestPool}.
     *
     * <p>A {@link FutureTask} (rather than a {@link CompletableFuture}) is used because it
     * <em>owns</em> the task: {@code cancel(true)} interrupts the thread running the body, so a
     * command blocked in interruptible I/O unwinds promptly instead of running to completion.
     * {@code done()} is the completion hook — it runs once when the body returns, throws, or is
     * cancelled, and is the sole owner of slot release + dispatch-next. {@code activeRequest} is
     * assigned before {@code execute}, so the body can never reach {@code done()} before the slot
     * is wired (no submit-then-assign race). The body passes its own identity to {@link
     * #sendTerminal} so a cancelled body whose interrupt lands between blocking points — and thus
     * reaches {@code sendTerminal} before unwinding — is still suppressed once the slot has moved
     * on.
     *
     * @param session the session that owns the request
     * @param req the request frame to dispatch
     */
    private void dispatchRequestLocked(@NonNull Session session, Frame.@NonNull Request req) {
        // Caller holds requestLock.
        //
        // holder lets the body Callable reference the task it runs in — Java definite-assignment
        // forbids referencing `task` within its own initializer (the Callable is an argument to the
        // FutureTask constructor). holder is final and set before execute, so the body always
        // observes the task when it reaches sendTerminal.
        final Future<?>[] holder = new Future<?>[1];
        FutureTask<Void> task =
                new FutureTask<>(
                        () ->
                                new DelegatingSecurityContextCallable<Void>(
                                                () -> {
                                                    try {
                                                        Frame.TerminalResponse response =
                                                                registry.dispatch(
                                                                        session.sender, req.raw());
                                                        sendTerminal(session, response, holder[0]);
                                                        return null;
                                                    } catch (Throwable t) {
                                                        // Last line of defense. A command may throw
                                                        // an Error (e.g.
                                                        // a native
                                                        // vault KDF failure) that escapes every
                                                        // catch(Exception)
                                                        // above.
                                                        // FutureTask.run() would swallow it
                                                        // silently, leaving the
                                                        // terminal
                                                        // hung with no diagnostic. Log the full
                                                        // trace and surface
                                                        // an error
                                                        // response so the user sees the failure;
                                                        // sendTerminal's
                                                        // exactly-once
                                                        // guard (terminalSent) suppresses it if the
                                                        // session is
                                                        // already
                                                        // closing/cancelled, so this never races
                                                        // the cancel path.
                                                        log.error(
                                                                "REQ  {}: dispatch threw",
                                                                peerLabel(session.identity),
                                                                t);
                                                        sendTerminal(
                                                                session,
                                                                Frame.Error.ofError(
                                                                        "Internal error: " + t),
                                                                holder[0]);
                                                        return null;
                                                    }
                                                },
                                                // Resolve at execution time: logout can revoke a
                                                // queued request.
                                                ExecutionSecurity.contextFor(
                                                        session.sender.userId()))
                                        .call()) {
                    // Sole owner of slot release + dispatch-next. Runs once — when the body
                    // returns, throws, or is cancelled (cancel(true) interrupts the body, then
                    // calls done()).
                    @Override
                    protected void done() {
                        session.requestLock.lock();
                        try {
                            if (session.activeRequest == this) {
                                session.activeRequest = null;
                                // Dispatch the next queued request (if any).
                                dispatchNextOrIdleLocked(session);
                            }
                        } finally {
                            session.requestLock.unlock();
                        }
                    }
                };
        holder[0] = task;

        session.activeRequest = task;
        session.terminalSent = false; // fresh exactly-once slot for this request
        log.trace("REQ  {}: dispatched", peerLabel(session.identity));
        requestPool.execute(task);
    }

    /**
     * Dispatch-next-or-idle: if there is a queued request, dispatch it; otherwise the session goes
     * idle.
     *
     * <p><b>Caller must hold {@link Session#requestLock}.</b>
     */
    private void dispatchNextOrIdleLocked(@NonNull Session session) {
        Frame.Request next = session.pendingRequests.pollFirst();
        if (next != null) {
            log.trace("REQ  {}: dequeuing next pending request", peerLabel(session.identity));
            dispatchRequestLocked(session, next);
        }
    }

    /**
     * Sends a command's terminal frame, claiming the request's exactly-once terminal slot first.
     *
     * <p>Two guards, both under {@link Session#requestLock}:
     *
     * <ul>
     *   <li><b>Identity</b> — {@code session.activeRequest == owner}. A cancel completes the
     *       in-flight future early to release the slot for the next request; the cancelled body
     *       keeps running until it winds down, then calls this. By then the slot belongs to a later
     *       request (or is null), so {@code owner} no longer matches and the stale frame is
     *       suppressed. Without this, that orphaned frame would leak into the next request's slot.
     *   <li><b>Flag</b> — {@code !terminalSent}. Mediates cancel vs normal completion for the
     *       <em>same</em> request: whichever of this method or the cancel handler reaches the flag
     *       first sends; the other finds it set and sends nothing.
     * </ul>
     *
     * @param session the session owning the in-flight command
     * @param frame the terminal frame ({@link Frame.Done}/{@link Frame.Error}/{@link
     *     Frame.Terminate}) to send
     * @param owner the future owning this body — its own identity, to prove the slot is still its
     */
    private void sendTerminal(
            @NonNull Session session, Frame.@NonNull TerminalResponse frame, Future<?> owner) {
        session.requestLock.lock();
        try {
            if (session.activeRequest == owner && !session.terminalSent) {
                session.terminalSent = true;
                send(session.identity, frame);
            }
        } finally {
            session.requestLock.unlock();
        }
    }

    /** Runs on the IO owner; elapsed monotonic time is unaffected by wall-clock adjustments. */
    private void evictIdleSessions() {
        long now = System.nanoTime();
        for (var session : sessions.values()) {
            if (now - session.lastActivityNanos >= TimeUnit.MILLISECONDS.toNanos(SESSION_TIMEOUT_MS)) {
                send(session.identity, new Frame.Terminate("Session timed out."));
                closeSession(session);
            }
        }
    }

    // ── Session lifecycle helpers ─────────────────────────────────────────

    /** Revokes account authentication while leaving terminals connected for a fresh login. */
    public void revokeUser(@NonNull UUID userId) {
        for (var session : sessions.values()) {
            if (userId.equals(session.sender.userId())) {
                session.sender.setUser(null);
                if (session.sender.cancelCurrentPrompt()) {
                    log.debug("Cancelled pending input for revoked user {}", userId);
                }
            }
        }
    }

    /**
     * Idempotently closes a session. Uses {@link AtomicBoolean#compareAndSet} so concurrent calls
     * from the session worker, IO owner, or server shutdown are all safe.
     *
     * <p>The pending queue is cleared and the in-flight task (if any) is {@code cancel(true)}'d so
     * its {@code done()} hook clears the slot (and dispatches nothing — the queue is already
     * empty). {@code cancel(true)} interrupts the body thread; if it is blocked in interruptible
     * I/O it unwinds promptly, otherwise it winds down on its own. Either way its late {@link
     * #sendTerminal} is suppressed by the identity guard (the slot is gone) plus the claimed flag.
     */
    private void closeSession(@NonNull Session session) {
        if (session.closed.compareAndSet(false, true)) {
            session.requestLock.lock();
            try {
                Future<?> task = session.activeRequest;
                session.terminalSent = true; // suppress the in-flight body's late terminal frame
                session.pendingRequests.clear();
                if (task != null) {
                    // Clear the queue BEFORE cancelling so done()'s dispatch-next finds
                    // nothing to dispatch on this dying session.
                    task.cancel(true);
                }
            } finally {
                session.requestLock.unlock();
            }
            sessions.remove(session.identity);
            log.debug("Session closed for {}", peerLabel(session.identity));
        }
    }

    // ── Outbox helper ─────────────────────────────────────────────────────

    /**
     * Enqueues a frame to be sent to the specified terminal by the IO thread.
     *
     * <p>Thread-safe: may be called from any thread. The IO thread is the sole dequeuer.
     *
     * @param identity the ZMQ DEALER identity of the target terminal
     * @param frame the frame to send
     */
    public void send(@NonNull String identity, Frame.@NonNull ServerFrame frame) {
        if (running && !outbox.offer(new OutboxEntry(identity, frame))) {
            log.warn("Terminal outbox is full; closing peer {}", peerLabel(identity));
            var session = sessions.get(identity);
            if (session != null) closeSession(session);
        }
    }

    /**
     * Factory method that creates a new {@link VetoCommandSender} for a freshly connected session.
     *
     * <p>The sender starts in the logged-out state ({@code username = null}); it is updated to the
     * authenticated user after a successful {@code /login} command.
     *
     * @param identity the ZMQ DEALER identity of the connecting terminal
     * @param clientProductVersion the product version the terminal reported in its {@link
     *     Frame.Hello} handshake
     * @param cwd the current working directory the terminal reported in its {@link Frame.Hello}
     *     handshake, mapped to the session's workspace at {@code /session create} time; never
     *     {@code null} - the terminal always reports its JVM working dir
     * @return a new, unauthenticated {@link VetoCommandSender}; never {@code null}
     */
    private @NonNull VetoCommandSender createSender(
            @NonNull String identity, @NonNull Version clientProductVersion, @NonNull String cwd) {
        return new VetoCommandSender(this, null, identity, clientProductVersion, cwd);
    }

    // ── Types ─────────────────────────────────────────────────────────────

    /** A frame that has been queued for sending by the IO thread. */
    private record OutboxEntry(@NonNull String identity, Frame.@NonNull ServerFrame frame) {}

    /**
     * All mutable state for a single connected terminal session.
     *
     * <p>The request lifecycle fields ({@link #activeRequest}, {@link #pendingRequests}, {@link
     * #terminalSent}) are protected by {@link #requestLock}. The lock is held only for brief state
     * transitions — never during {@code registry.dispatch}, which runs outside the lock in the
     * request pool.
     */
    static class Session {
        final @NonNull String identity;
        final @NonNull VetoCommandSender sender;

        /** Monotonic time of the last received frame; read by the IO owner. */
        volatile long lastActivityNanos = System.nanoTime();

        /**
         * Incoming frame mailbox. Written by the IO thread via {@link #routeFrame}; consumed in
         * FIFO order by the session worker.
         */
        final @NonNull BlockingQueue<@NonNull Frame> mailbox = new LinkedBlockingQueue<>();

        /**
         * Per-session lock protecting the request lifecycle fields: {@link #activeRequest}, {@link
         * #pendingRequests}, {@link #terminalSent}. The session worker and the request-pool {@code
         * done()} hook both acquire this lock for compound operations that must be atomic as a
         * group (e.g. checking in-flight + enqueue, or clearing in-flight + polling next request).
         * Different sessions do not contend — each has its own lock.
         */
        final @NonNull ReentrantLock requestLock = new ReentrantLock();

        /**
         * Pending request queue. When a {@link Frame.Request} arrives while another is already
         * in-flight ({@link #activeRequest} is non-null), it is appended here. The {@code
         * dispatchNextOrIdleLocked} callback polls the next request and dispatches it, implementing
         * server-side 1:1 request serialization. This mirrors the client-side {@code
         * ClientSession.pendingRequests} queue.
         *
         * <p>Protected by {@link #requestLock}.
         */
        final @NonNull Deque<Frame.@NonNull Request> pendingRequests = new ArrayDeque<>();

        /**
         * The in-flight request task, or {@code null} if no command is running. A {@link
         * FutureTask} so {@code cancel(true)} interrupts the body thread (early-cancel at its I/O
         * wait point); its {@code done()} hook is the sole path that clears this slot. Used for
         * routing decisions (dispatch-or-queue) and to gate the two-level cancel.
         *
         * <p>Protected by {@link #requestLock}.
         */
        Future<?> activeRequest;

        /**
         * Whether a terminal frame has been sent for the currently in-flight request. Claimed
         * (false→true) under {@link #requestLock} by whichever of {@code sendTerminal} or the
         * cancel handler wins the race — guaranteeing exactly one terminal frame per {@link
         * Frame.Request} (a cancel racing a normal completion produces only one). Reset to {@code
         * false} when a new request is dispatched; set to {@code true} by {@code closeSession} to
         * suppress a lingering body's frame after teardown.
         *
         * <p>Protected by {@link #requestLock} (a plain boolean — the lock serializes the
         * check-and-set, so no {@link AtomicBoolean} is needed).
         */
        boolean terminalSent;

        /**
         * Closed flag. Set via {@link AtomicBoolean#compareAndSet} to guarantee exactly-once
         * session teardown even when multiple threads race to close the same session.
         */
        final @NonNull AtomicBoolean closed = new AtomicBoolean(false);

        Session(@NonNull String identity, @NonNull VetoCommandSender sender) {
            this.identity = identity;
            this.sender = sender;
        }
    }
}
