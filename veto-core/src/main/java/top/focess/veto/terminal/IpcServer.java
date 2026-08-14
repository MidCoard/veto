package top.focess.veto.terminal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
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
import top.focess.veto.contract.IpcClient;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcFrame.HintInfo;
import top.focess.veto.contract.IpcMeta;
import top.focess.veto.contract.ServerTransport;
import top.focess.veto.contract.Transport;
import top.focess.veto.contract.Version;
import top.focess.veto.contract.ZmqChannel;
import top.focess.veto.vault.UserContext;

/**
 * Backend IPC server — the {@link IpcClient} counterpart. Multiplexes many terminal sessions over a
 * single ZMQ ROUTER socket. (The asymmetry with {@link IpcClient}'s single-DEALER, single-socket
 * shape is by design: the server is 1:N, the client is 1:1 — they are not mirror images.)
 *
 * <h3>Three-pool threading model</h3>
 *
 * <ul>
 *   <li><b>Pool 1 — Infrastructure</b> (2 fixed platform threads): runs {@link #ioLoop} and {@link
 *       #heartbeatLoop}. The IO thread is the <em>sole</em> owner of the transport; no other thread
 *       ever calls {@link ServerTransport#recv(long)} or {@link ServerTransport#send(String,
 *       IpcFrame)}.
 *   <li><b>Pool 2 — Session workers</b> (one virtual thread per connected terminal): each session
 *       has a dedicated {@link BlockingQueue} mailbox. The session worker drains that mailbox and
 *       processes non-Request frames <em>synchronously</em>, preserving per-session ordering
 *       without any explicit locking. {@link IpcFrame.Request} frames are submitted to Pool 3.
 *   <li><b>Pool 3 — Request pool</b> (virtual thread per task): executes {@code registry.dispatch},
 *       which may block for an extended period (AI inference, tool calls, etc.). The server
 *       enforces 1:1 request serialization: at most one request runs at a time per session;
 *       additional requests are queued in {@link Session#pendingRequests} and dispatched
 *       sequentially (dispatch-next-or-idle) when the in-flight request completes.
 * </ul>
 *
 * <h3>Per-session request lock</h3>
 *
 * <p>The request lifecycle — checking whether a request is in-flight, enqueuing to / polling from
 * the pending queue, setting / clearing the in-flight future, and sending the terminal frame —
 * involves compound operations on multiple fields that must be atomic as a group. A CAS on a single
 * {@code AtomicBoolean} only makes that one bit-flip atomic; it cannot protect the surrounding code
 * from racing with another thread's CAS + surrounding code. A per-session {@link ReentrantLock}
 * ({@link Session#requestLock}) makes the entire compound operation atomic.
 *
 * <p>The lock is held only for brief state transitions (never during {@code registry.dispatch},
 * which runs outside the lock in the request pool). Different sessions do not contend — each has
 * its own lock.
 *
 * <h3>Frame routing</h3>
 *
 * <ul>
 *   <li>{@link IpcFrame.Hello} — handled directly on the IO thread (fast path; session doesn't
 *       exist yet). On success the session is created and its worker virtual thread is spawned.
 *   <li>All other frames — enqueued to the session's mailbox via {@link Session#mailbox} and
 *       processed in arrival order by the session worker.
 * </ul>
 *
 * <h3>Session lifecycle</h3>
 *
 * <ul>
 *   <li>Created on {@link IpcFrame.Hello} (IO thread).
 *   <li>Closed on {@link IpcFrame.Bye} (session worker), heartbeat timeout (heartbeat thread), or
 *       server shutdown. Closing is idempotent via {@link Session#closed} ({@link AtomicBoolean}).
 * </ul>
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

    /**
     * Outbox queue: any thread may enqueue; only the IO thread dequeues and sends. Using {@link
     * ConcurrentLinkedQueue} here avoids blocking the IO thread on backpressure.
     */
    private final @NonNull ConcurrentLinkedQueue<@NonNull OutboxEntry> outbox =
            new ConcurrentLinkedQueue<>();

    /**
     * Live count of {@link #outbox} entries, maintained alongside the queue so the backpressure
     * check in {@link #send} is O(1) — {@link ConcurrentLinkedQueue#size()} is O(n) and {@code
     * send} sits on the hot streaming-output path.
     */
    private final @NonNull AtomicInteger outboxSize = new AtomicInteger();

    /** Active sessions keyed by ZMQ identity string. */
    private final @NonNull ConcurrentHashMap<@NonNull String, @NonNull Session> sessions =
            new ConcurrentHashMap<>();

    /**
     * Pool 1 — fixed platform threads for the IO loop and heartbeat loop. Platform threads are
     * preferred here because these are long-lived, CPU-aware tight loops that should not be subject
     * to virtual-thread pinning or carrier-thread scheduling delays.
     */
    private final @NonNull ExecutorService infraPool =
            Executors.newFixedThreadPool(2, Thread.ofPlatform().name("veto-infra-", 0).factory());

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

    private @NonNull ZContext ctx;
    private @NonNull ServerTransport transport;
    private volatile boolean running;

    @Value("${veto.terminal.bind-address:tcp://127.0.0.1:5555}")
    private @NonNull String bindAddress = "tcp://127.0.0.1:5555";

    /**
     * Constructs a new {@code IpcServer}. Spring calls this constructor with the {@link
     * CommandRegistry} and {@link AgentService} beans wired from the application context.
     *
     * @param registry the command registry used to dispatch requests and produce completions
     * @param agentService the agent service used to resolve/decline pending HITL vetoes
     */
    @SuppressWarnings("initialization.fields.uninitialized")
    public IpcServer(@NonNull CommandRegistry registry, @NonNull AgentService agentService) {
        this.registry = registry;
        this.agentService = agentService;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Initializes the ZeroMQ context, binds the ROUTER socket, and starts the IO and heartbeat
     * infrastructure threads.
     *
     * <p>Invoked automatically by Spring after the bean is constructed ({@link PostConstruct}). The
     * bind address is read from the {@code veto.terminal.bind-address} property, defaulting to
     * {@code tcp://127.0.0.1:5555}.
     */
    @PostConstruct
    public void start() {
        ctx = new ZContext();
        transport = ZmqChannel.Server.bindRouter(ctx, bindAddress);
        running = true;
        infraPool.submit(this::ioLoop);
        infraPool.submit(this::heartbeatLoop);
        log.info("IpcServer bound to {}", bindAddress);
    }

    /**
     * Gracefully shuts down the server.
     *
     * <p>Invoked automatically by Spring before the bean is destroyed ({@link PreDestroy}). The
     * shutdown sequence is:
     *
     * <ol>
     *   <li>Sets {@link #running} to {@code false} so loops exit after their current iteration.
     *   <li>Sends a {@link IpcFrame.Terminate} frame to every connected terminal.
     *   <li>Waits 100 ms to allow the IO thread to flush outgoing terminate frames.
     *   <li>Shuts down session and request pools ({@code shutdownNow}).
     *   <li>Awaits infrastructure pool termination (up to 3 seconds).
     *   <li>Closes the transport socket and ZMQ context.
     * </ol>
     */
    @PreDestroy
    public void stop() {
        // Notify all connected terminals BEFORE flipping `running` — the IO loop must still be
        // running to drain these Terminate frames. (ioLoop also does a final drain on exit, so
        // late frames are flushed too; enqueuing while running avoids the race where the loop
        // exits before they're sent.)
        for (Session session : sessions.values()) {
            send(session.identity, new IpcFrame.Terminate("Server shutting down."));
        }
        // Brief pause to let the IO loop flush the Terminate frames while it is still running.
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
        }
        running = false; // signal loops to exit (ioLoop does a final outbox drain on the way out)
        // Shut down pools in dependency order: sessions first (they enqueue to requestPool),
        // then requests, then infrastructure (IO thread drains outbox).
        sessionPool.shutdownNow();
        requestPool.shutdownNow();
        infraPool.shutdownNow();
        try {
            boolean terminated = infraPool.awaitTermination(3, TimeUnit.SECONDS);
            if (!terminated) {
                log.warn("Infrastructure pool did not terminate within 3 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        transport.close();
        ctx.close();
        log.info("IpcServer stopped");
    }

    // ── Pool 1 — IO loop ─────────────────────────────────────────────────

    /**
     * The main IO event loop. Runs on a dedicated platform thread and is the <em>only</em> thread
     * allowed to read from or write to the transport.
     *
     * <p>On each iteration it:
     *
     * <ol>
     *   <li>Receives one frame from the transport (with a short timeout so outbox draining is still
     *       responsive) and routes it via {@link #routeFrame}.
     *   <li>Drains the outbox and sends all queued response frames.
     * </ol>
     */
    private void ioLoop() {
        while (running) {
            // Use 0 ms timeout when there is pending outgoing work to minimise latency.
            long timeout = outbox.isEmpty() ? 50 : 0;

            // Step 1 — receive one incoming frame and route it. The transport polls internally;
            // malformed payloads are dropped (and logged) by the transport, never surfaced.
            Transport.FramedMsg msg = transport.recv(timeout);
            if (msg != null) {
                routeFrame(msg.identity(), msg.frame());
            }

            // Step 2 — drain the outbox so responses reach terminals promptly.
            drainOutbox();
        }
        // Final drain: flush anything enqueued after `running` flipped (e.g. shutdown Terminate
        // frames, or a command's late output) before the transport closes.
        drainOutbox();
    }

    /**
     * Drains the outbox and sends every queued frame, decrementing {@link #outboxSize} per entry.
     * Never throws — logs send failures and continues.
     */
    private void drainOutbox() {
        OutboxEntry entry;
        while ((entry = outbox.poll()) != null) {
            outboxSize.decrementAndGet();
            try {
                transport.send(entry.identity, entry.frame);
            } catch (Exception e) {
                log.warn(
                        "Failed to send {} to {}",
                        entry.frame.getClass().getSimpleName(),
                        entry.identity,
                        e);
            }
        }
    }

    /**
     * Routes a frame that just arrived from the ZMQ socket.
     *
     * <p>{@link IpcFrame.Hello} is handled synchronously here on the IO thread: the session does
     * not exist yet, so there is no mailbox to enqueue into. Every other frame is enqueued to the
     * session's mailbox for ordered processing by the session worker.
     *
     * <p>Must only be called from the IO thread.
     */
    private void routeFrame(@NonNull String identity, @NonNull IpcFrame frame) {
        if (frame instanceof IpcFrame.Hello hello) {
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
                    identity.substring(0, 8));
            send(
                    identity,
                    new IpcFrame.Terminate(
                            "Session no longer valid (server restarted?) — please reconnect."));
            return;
        }
        // Enqueue to the session mailbox; the session worker consumes frames in order.
        // LinkedBlockingQueue is unbounded by default, so offer should never fail — but
        // guard against it rather than silently dropping a frame.
        if (!session.mailbox.offer(frame)) {
            log.warn(
                    "Mailbox full for session {} — dropping {}",
                    identity.substring(0, 8),
                    frame.getClass().getSimpleName());
        }
    }

    /**
     * Handles a {@link IpcFrame.Hello} handshake directly on the IO thread.
     *
     * <p>Rejects the connection if an active session already exists for the given identity.
     * Otherwise creates the session, starts its worker virtual thread, and sends {@link
     * IpcFrame.Welcome} back.
     */
    private void handleHello(@NonNull String identity, IpcFrame.@NonNull Hello hello) {
        if (sessions.containsKey(identity)) {
            // The IO thread is the only writer to `sessions`, so containsKey + put is safe here.
            log.warn("Duplicate identity {} — rejecting handshake", identity.substring(0, 8));
            send(identity, new IpcFrame.Error("Duplicate identity connected.", hello.seq()));
            return;
        }
        Version clientProductVersion = hello.productVersion();
        Session session =
                new Session(identity, createSender(identity, clientProductVersion, hello.cwd()));
        sessions.put(identity, session);
        // Spawn the session worker — virtual thread parks on mailbox.take between frames.
        sessionPool.submit(() -> sessionLoop(session));

        int negotiated = Math.min(hello.version(), IpcFrame.PROTOCOL_VERSION);
        log.debug(
                "HELLO {}: v{} → negotiated v{} (client product {})",
                identity.substring(0, 8),
                hello.version(),
                negotiated,
                clientProductVersion);
        send(identity, new IpcFrame.Welcome(negotiated, hello.seq(), VetoVersion.VERSION));
    }

    // ── Pool 2 — Session worker loop ─────────────────────────────────────

    /**
     * The per-session event loop. Runs on a virtual thread from {@link #sessionPool}.
     *
     * <p>Blocks on {@link Session#mailbox} and processes each frame sequentially. This guarantees
     * per-session ordering with no synchronization overhead — only one thread ever processes a
     * given session's frames at a time. {@link IpcFrame.Request} frames are the single exception:
     * they are submitted to {@link #requestPool} so long-running commands never stall this loop.
     */
    private void sessionLoop(@NonNull Session session) {
        log.debug("Session worker started for {}", session.identity.substring(0, 8));
        while (!session.closed.get() && running) {
            IpcFrame frame;
            try {
                // Poll with a 1-second timeout so we re-check `running` and `closed` periodically.
                frame = session.mailbox.poll(1, TimeUnit.SECONDS);
                if (frame == null) continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            session.lastActivityMillis = System.currentTimeMillis();
            handleSessionFrame(session, frame);
        }
        // Ensure the session is cleaned up when the loop exits (e.g. server shutdown
        // without an explicit Bye). closeSession is idempotent — if it was already called
        // (Bye, heartbeat timeout), the CAS on `closed` makes this a no-op.
        closeSession(session);
        log.debug("Session worker stopped for {}", session.identity.substring(0, 8));
    }

    /**
     * Dispatches a single frame on the session worker thread.
     *
     * <p>All frames except {@link IpcFrame.Request} are handled inline — they are fast, stateful
     * operations that must run in order relative to each other (e.g. {@link IpcFrame.Cancel} must
     * see the futures that were registered by previous {@link IpcFrame.Request} dispatches). {@link
     * IpcFrame.Request} is the only frame type that may block for a significant duration and is
     * therefore off-loaded to {@link #requestPool}.
     */
    @SuppressWarnings(
            "LoggingSimilarMessage") // Request/result trace pairs intentionally share a prefix.
    private void handleSessionFrame(@NonNull Session session, @NonNull IpcFrame frame) {
        String identity = session.identity;
        String user = session.sender.username();
        if (user != null) {
            UserContext.set(user);
        }
        try {
            switch (frame) {
                case IpcFrame.Request req -> {
                    // 1:1 dispatch: if no request is in-flight, dispatch; otherwise queue.
                    session.requestLock.lock();
                    try {
                        if (session.activeRequest != null) {
                            session.pendingRequests.addLast(req);
                            log.trace(
                                    "REQ  {}: queued (in-flight request already running)",
                                    identity.substring(0, 8));
                        } else {
                            dispatchRequestLocked(session, req);
                        }
                    } finally {
                        session.requestLock.unlock();
                    }
                }

                case IpcFrame.Input in -> {
                    log.trace("IN   {}", identity.substring(0, 8));
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
                                identity.substring(0, 8),
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
                                    identity.substring(0, 8));
                        }
                    }
                }

                case IpcFrame.Complete comp -> {
                    log.trace("COMP {}: {}", identity.substring(0, 8), comp.raw());
                    var completions = registry.complete(session.sender, comp.raw());
                    log.trace(
                            "COMP {}: → {} candidates",
                            identity.substring(0, 8),
                            completions.size());
                    send(identity, new IpcFrame.CompleteResult(completions, comp.seq()));
                }

                case IpcFrame.Hint h -> {
                    log.trace("HINT {}: {}", identity.substring(0, 8), h.raw());
                    HintInfo hint = registry.hint(session.sender, h.raw());
                    log.trace(
                            "HINT {}: → {}",
                            identity.substring(0, 8),
                            hint == HintInfo.EMPTY ? "EMPTY" : hint.displayText());
                    send(identity, new IpcFrame.HintResult(hint, h.seq()));
                }

                case IpcFrame.Cancel c -> {
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
                                log.trace(
                                        "CANC {}: dismissed current prompt",
                                        identity.substring(0, 8));
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
                                        identity.substring(0, 8),
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
                                        new IpcFrame.Done(Map.of(IpcMeta.CANCELLED, true), null));
                                if (task != null) {
                                    task.cancel(true);
                                }
                                log.trace(
                                        "CANC {}: cancelled in-flight request",
                                        identity.substring(0, 8));
                            }
                            // else: the body already sent its terminal frame — send nothing.
                        } else {
                            // No in-flight request — the terminal thinks one is running and is
                            // blocked awaiting a terminal frame. Send an Error to unblock it.
                            send(
                                    identity,
                                    IpcFrame.Error.ofError("No in-flight request to cancel."));
                            log.trace(
                                    "CANC {}: no in-flight request — sent error",
                                    identity.substring(0, 8));
                        }
                    } finally {
                        session.requestLock.unlock();
                    }
                }

                case IpcFrame.Bye b -> {
                    log.trace("BYE  {}: terminal disconnecting", identity.substring(0, 8));
                    // Bye is fire-and-forget — the client tears down without waiting, and the
                    // server closes on receipt without sending anything back (no Done). Closing
                    // is idempotent; closeSession sets closed=true so the session loop exits.
                    closeSession(session);
                }

                case IpcFrame.Heartbeat h ->
                        // Heartbeat updates the timestamp; the heartbeat loop checks this value.
                        session.lastActivityMillis = System.currentTimeMillis();

                default -> {
                    if (frame instanceof IpcFrame.Unknown(String type)) {
                        log.warn(
                                "Unknown frame type '{}' from {} — protocol version mismatch?",
                                type,
                                identity.substring(0, 8));
                    }
                }
            }
        } finally {
            UserContext.clear();
        }
    }

    // ── Request dispatch ──────────────────────────────────────────────────

    /**
     * Dispatches a {@link IpcFrame.Request} to the request pool and wires up the completion hook.
     *
     * <p>Caller must hold {@link Session#requestLock}. Builds a {@link FutureTask} that runs {@link
     * CommandRegistry#dispatch}, stores it in {@code session.activeRequest}, resets {@link
     * Session#terminalSent}, then submits it to {@link #requestPool}.
     *
     * <p>A {@link FutureTask} (rather than a {@link java.util.concurrent.CompletableFuture}) is
     * used because it <em>owns</em> the task: {@code cancel(true)} interrupts the thread running
     * the body, so a command blocked in interruptible I/O unwinds promptly instead of running to
     * completion. {@code done()} is the completion hook — it runs once when the body returns,
     * throws, or is cancelled, and is the sole owner of slot release + dispatch-next. {@code
     * activeRequest} is assigned before {@code execute}, so the body can never reach {@code done()}
     * before the slot is wired (no submit-then-assign race). The body passes its own identity to
     * {@link #sendTerminal} so a cancelled body whose interrupt lands between blocking points — and
     * thus reaches {@code sendTerminal} before unwinding — is still suppressed once the slot has
     * moved on.
     *
     * @param session the session that owns the request
     * @param req the request frame to dispatch
     */
    private void dispatchRequestLocked(@NonNull Session session, IpcFrame.@NonNull Request req) {
        // Caller holds requestLock.
        //
        // holder lets the body Callable reference the task it runs in — Java definite-assignment
        // forbids referencing `task` within its own initializer (the Callable is an argument to the
        // FutureTask constructor). holder is final and set before execute, so the body always
        // observes the task when it reaches sendTerminal.
        final Future<?>[] holder = new Future<?>[1];
        FutureTask<Void> task =
                new FutureTask<>(
                        () -> {
                            // UserContext is a ThreadLocal — set it on THIS (request-pool)
                            // thread, where registry.dispatch runs. The set in
                            // handleSessionFrame runs on the session-worker thread and does not
                            // propagate across requestPool.execute.
                            String user = session.sender.username();
                            if (user != null) {
                                UserContext.set(user);
                            }
                            try {
                                IpcFrame.TerminalResponse response =
                                        registry.dispatch(session.sender, req.raw());
                                sendTerminal(session, response, holder[0]);
                                return null;
                            } catch (Throwable t) {
                                // Last line of defense. A command may throw an Error (e.g. a native
                                // vault KDF failure) that escapes every catch(Exception) above.
                                // FutureTask.run() would swallow it silently, leaving the terminal
                                // hung with no diagnostic. Log the full trace and surface an error
                                // response so the user sees the failure; sendTerminal's
                                // exactly-once
                                // guard (terminalSent) suppresses it if the session is already
                                // closing/cancelled, so this never races the cancel path.
                                log.error(
                                        "REQ  {}: dispatch threw",
                                        session.identity.substring(0, 8),
                                        t);
                                sendTerminal(
                                        session,
                                        IpcFrame.Error.ofError("Internal error: " + t),
                                        holder[0]);
                                return null;
                            } finally {
                                UserContext.clear();
                            }
                        }) {
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
        log.trace("REQ  {}: dispatched", session.identity.substring(0, 8));
        requestPool.execute(task);
    }

    /**
     * Dispatch-next-or-idle: if there is a queued request, dispatch it; otherwise the session goes
     * idle.
     *
     * <p><b>Caller must hold {@link Session#requestLock}.</b>
     */
    private void dispatchNextOrIdleLocked(@NonNull Session session) {
        IpcFrame.Request next = session.pendingRequests.pollFirst();
        if (next != null) {
            log.trace("REQ  {}: dequeuing next pending request", session.identity.substring(0, 8));
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
     * @param frame the terminal frame ({@link IpcFrame.Done}/{@link IpcFrame.Error}/{@link
     *     IpcFrame.Terminate}) to send
     * @param owner the future owning this body — its own identity, to prove the slot is still its
     */
    private void sendTerminal(
            @NonNull Session session, IpcFrame.@NonNull TerminalResponse frame, Future<?> owner) {
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

    // ── Pool 1 — Heartbeat loop ───────────────────────────────────────────

    /**
     * Periodically scans all active sessions and evicts any that have been silent for longer than
     * {@link #SESSION_TIMEOUT_MS}. Runs on a dedicated infrastructure platform thread.
     *
     * <p>Checking at {@link #HEARTBEAT_CHECK_MS} intervals (⅓ of the timeout) bounds the worst-case
     * eviction lag to {@code SESSION_TIMEOUT_MS + HEARTBEAT_CHECK_MS}.
     */
    @SuppressWarnings("BusyWait") // Deliberate fixed-rate heartbeat timeout scan, not a spin loop.
    private void heartbeatLoop() {
        while (running) {
            try {
                Thread.sleep(HEARTBEAT_CHECK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            long cutoff = System.currentTimeMillis() - SESSION_TIMEOUT_MS;
            for (Session session : sessions.values()) {
                if (session.lastActivityMillis < cutoff && !session.closed.get()) {
                    log.info("Evicting timed-out session {}", session.identity.substring(0, 8));
                    // Notify the terminal before closing so it can display a message.
                    send(session.identity, new IpcFrame.Terminate("Session timed out."));
                    closeSession(session);
                }
            }
        }
    }

    // ── Session lifecycle helpers ─────────────────────────────────────────

    /**
     * Idempotently closes a session. Uses {@link AtomicBoolean#compareAndSet} so concurrent calls
     * from the session worker, heartbeat thread, or server shutdown are all safe.
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
            log.debug("Session closed for {}", session.identity.substring(0, 8));
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
    public void send(@NonNull String identity, @NonNull IpcFrame frame) {
        int size = outboxSize.incrementAndGet();
        if (size > MAX_OUTBOX_SIZE) {
            outboxSize.decrementAndGet(); // not actually enqueuing — undo the reservation
            log.warn(
                    "Outbox congested ({} entries) — dropping {} for {}",
                    size,
                    frame.getClass().getSimpleName(),
                    identity);
            return;
        }
        outbox.add(new OutboxEntry(identity, frame));
    }

    /**
     * Factory method that creates a new {@link VetoCommandSender} for a freshly connected session.
     *
     * <p>The sender starts in the logged-out state ({@code username = null}); it is updated to the
     * authenticated user after a successful {@code /login} command.
     *
     * @param identity the ZMQ DEALER identity of the connecting terminal
     * @param clientProductVersion the product version the terminal reported in its {@link
     *     IpcFrame.Hello} handshake
     * @param cwd the current working directory the terminal reported in its {@link IpcFrame.Hello}
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
    public record OutboxEntry(@NonNull String identity, @NonNull IpcFrame frame) {}

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

        /** Timestamp of the last received frame; read by the heartbeat thread. */
        volatile long lastActivityMillis = System.currentTimeMillis();

        /**
         * Incoming frame mailbox. Written by the IO thread via {@link #routeFrame}; consumed in
         * FIFO order by the session worker.
         */
        final @NonNull BlockingQueue<@NonNull IpcFrame> mailbox = new LinkedBlockingQueue<>();

        /**
         * Per-session lock protecting the request lifecycle fields: {@link #activeRequest}, {@link
         * #pendingRequests}, {@link #terminalSent}. The session worker and the request-pool {@code
         * done()} hook both acquire this lock for compound operations that must be atomic as a
         * group (e.g. checking in-flight + enqueue, or clearing in-flight + polling next request).
         * Different sessions do not contend — each has its own lock.
         */
        final @NonNull ReentrantLock requestLock = new ReentrantLock();

        /**
         * Pending request queue. When a {@link IpcFrame.Request} arrives while another is already
         * in-flight ({@link #activeRequest} is non-null), it is appended here. The {@code
         * dispatchNextOrIdleLocked} callback polls the next request and dispatches it, implementing
         * server-side 1:1 request serialization. This mirrors the client-side {@code
         * ClientSession.pendingRequests} queue.
         *
         * <p>Protected by {@link #requestLock}.
         */
        final @NonNull Deque<IpcFrame.@NonNull Request> pendingRequests = new ArrayDeque<>();

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
         * IpcFrame.Request} (a cancel racing a normal completion produces only one). Reset to
         * {@code false} when a new request is dispatched; set to {@code true} by {@code
         * closeSession} to suppress a lingering body's frame after teardown.
         *
         * <p>Protected by {@link #requestLock} (a plain boolean — the lock serializes the
         * check-and-set, so no {@link java.util.concurrent.atomic.AtomicBoolean} is needed).
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
