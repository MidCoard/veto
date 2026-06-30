package top.focess.veto.terminal;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.zeromq.ZContext;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.contract.IpcClient;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcFrame.HintInfo;
import top.focess.veto.contract.IpcMeta;
import top.focess.veto.contract.ServerTransport;
import top.focess.veto.contract.Transport;
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
 *       which may block for an extended period (AI inference, tool calls, etc.). Multiple
 *       concurrent requests per session are supported; each future is tracked so it can be
 *       cancelled by a subsequent {@link IpcFrame.Cancel}.
 * </ul>
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

    private static final Logger log = LoggerFactory.getLogger(IpcServer.class);

    private static final long SESSION_TIMEOUT_MS = 90_000;

    /** Check for stale sessions 3× per timeout window to bound the worst-case eviction lag. */
    private static final long HEARTBEAT_CHECK_MS = SESSION_TIMEOUT_MS / 3;

    private static final int MAX_OUTBOX_SIZE = 10_000;

    private final CommandRegistry registry;

    /**
     * Outbox queue: any thread may enqueue; only the IO thread dequeues and sends. Using {@link
     * ConcurrentLinkedQueue} here avoids blocking the IO thread on backpressure.
     */
    private final ConcurrentLinkedQueue<OutboxEntry> outbox = new ConcurrentLinkedQueue<>();

    /** Active sessions keyed by ZMQ identity string. */
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Pool 1 — fixed platform threads for the IO loop and heartbeat loop. Platform threads are
     * preferred here because these are long-lived, CPU-aware tight loops that should not be subject
     * to virtual-thread pinning or carrier-thread scheduling delays.
     */
    private final ExecutorService infraPool =
            Executors.newFixedThreadPool(2, Thread.ofPlatform().name("veto-infra-", 0).factory());

    /**
     * Pool 2 — one virtual thread per session. Each session worker blocks on its mailbox queue;
     * virtual threads are ideal here since they park cheaply while waiting for frames.
     */
    private final ExecutorService sessionPool = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Pool 3 — one virtual thread per Request task. Commands may block on I/O (AI streaming, DB
     * calls, etc.) for seconds to minutes; virtual threads scale well for this workload.
     */
    private final ExecutorService requestPool = Executors.newVirtualThreadPerTaskExecutor();

    private ZContext ctx;
    private ServerTransport transport;
    private volatile boolean running;

    @Value("${veto.terminal.bind-address:tcp://127.0.0.1:5555}")
    private String bindAddress;

    /**
     * Constructs a new {@code IpcServer}. Spring calls this constructor with the {@link
     * CommandRegistry} bean wired from the application context.
     *
     * @param registry the command registry used to dispatch requests and produce completions
     */
    public IpcServer(@NonNull CommandRegistry registry) {
        this.registry = registry;
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
        running = false;
        // Notify all connected terminals before closing the socket.
        for (Session session : sessions.values()) {
            send(session.identity, new IpcFrame.Terminate("Server shutting down."));
        }
        // Brief pause to allow Terminate frames to be flushed by the IO thread.
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
        }
        // Shut down pools in dependency order: sessions first (they enqueue to requestPool),
        // then requests, then infrastructure (IO thread drains outbox).
        sessionPool.shutdownNow();
        requestPool.shutdownNow();
        infraPool.shutdownNow();
        try {
            infraPool.awaitTermination(3, TimeUnit.SECONDS);
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
            OutboxEntry entry;
            while ((entry = outbox.poll()) != null) {
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
            log.warn(
                    "Received {} from unknown or closed session {} — ignoring",
                    frame.getClass().getSimpleName(),
                    identity.substring(0, 8));
            return;
        }
        // Enqueue to the session mailbox; the session worker consumes frames in order.
        session.mailbox.offer(frame);
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
        Session session = new Session(identity, createSender(identity));
        sessions.put(identity, session);
        // Spawn the session worker — virtual thread parks on mailbox.take between frames.
        sessionPool.submit(() -> sessionLoop(session));

        int negotiated = Math.min(hello.version(), IpcFrame.PROTOCOL_VERSION);
        log.debug(
                "HELLO {}: v{} → negotiated v{}",
                identity.substring(0, 8),
                hello.version(),
                negotiated);
        send(identity, new IpcFrame.Welcome(negotiated, hello.seq()));
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
        // Ensure any in-flight request tasks are cancelled when this session's loop exits.
        cancelAllRequests(session);
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
    private void handleSessionFrame(@NonNull Session session, @NonNull IpcFrame frame) {
        String identity = session.identity;
        String user = session.sender.username();
        if (user != null) {
            UserContext.set(user);
        }
        try {
            switch (frame) {
                case IpcFrame.Request req -> {
                    // A new command: reset the terminal-frame claim so this Request can resolve
                    // with
                    // exactly one terminal frame.
                    session.terminalSent.set(false);
                    // Off-load to the request pool so long-running commands never stall this loop.
                    // CompletableFuture.whenComplete self-removes from the tracking set once the
                    // task finishes (normally or exceptionally), eliminating the holder[] trick.
                    //
                    // Note: CompletableFuture.cancel(true) marks the future cancelled but does NOT
                    // interrupt the running thread (unlike Future from ExecutorService.submit).
                    // Command handlers should therefore also check
                    // Thread.currentThread.isInterrupted
                    // to be responsive to cancellation.
                    CompletableFuture<Void> task =
                            CompletableFuture.runAsync(
                                    () -> {
                                        if (user != null) {
                                            UserContext.set(user);
                                        }
                                        try {
                                            log.trace(
                                                    "REQ  {}: {}",
                                                    identity.substring(0, 8),
                                                    req.raw());
                                            try {
                                                IpcFrame.TerminalResponse result =
                                                        registry.dispatch(
                                                                session.sender, req.raw());
                                                session.lastActivityMillis =
                                                        System.currentTimeMillis();
                                                // Claim the single terminal frame : a cancel
                                                // may have already sent Done{cancelled}; if so,
                                                // this
                                                // command's real terminal is dropped (exactly-one).
                                                sendTerminal(session, result);
                                                log.trace(
                                                        "DONE {}: {}",
                                                        identity.substring(0, 8),
                                                        result.getClass().getSimpleName());
                                                if (result instanceof IpcFrame.Terminate) {
                                                    // : a command-Terminate (e.g. /exit) is
                                                    // session-terminal — the server sends it and
                                                    // then
                                                    // closes the session; no Bye is expected back.
                                                    closeSession(session);
                                                }
                                            } catch (Throwable t) {
                                                log.error(
                                                        "Unhandled error executing request for {}",
                                                        identity,
                                                        t);
                                                sendTerminal(
                                                        session,
                                                        IpcFrame.Error.ofError(
                                                                "Internal error: "
                                                                        + t.getMessage()));
                                            }
                                        } finally {
                                            UserContext.clear();
                                        }
                                    },
                                    requestPool);
                    session.activeRequests.add(task);
                    // Self-removal: runs on the CompletableFuture's completion thread after the
                    // task finishes, regardless of whether it completed normally or exceptionally.
                    task.whenComplete((ignored, ex) -> session.activeRequests.remove(task));
                }

                case IpcFrame.Input in -> {
                    log.trace("IN   {}", identity.substring(0, 8));
                    boolean accepted = session.sender.receiveInput(in.raw());
                    if (!accepted) {
                        // ACTIVE × Input with no waiting request: discard + log. No Error
                        // frame
                        // — the terminal only sends Input in reply to a Prompt (client routing), so
                        // an unaccepted Input means no command awaits input; an Error would break
                        // the exactly-one-terminal-frame invariant for an unrelated Request.
                        log.trace(
                                "Input from {} with no waiting request — discarding",
                                identity.substring(0, 8));
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
                    if (session.activeRequests.isEmpty()) {
                        // : no command in flight — the Request already resolved (its terminal
                        // frame is sent or in flight). Send nothing: a second terminal frame would
                        // break the exactly-one-terminal-frame invariant for that Request.
                        log.trace(
                                "CANC {}: no in-flight request — no-op", identity.substring(0, 8));
                    } else {
                        // Claim this command's single terminal frame FIRST, so Done{cancelled} wins
                        // the race against the command unwinding (its cancelled input throws → an
                        // Error that the dispatch task will then drop). Then cooperatively unblock
                        // any input the command is parked in so it does not wait out its
                        // timeout, and cancel the task futures.
                        boolean cancelled = session.terminalSent.compareAndSet(false, true);
                        session.sender.cancelInput();
                        cancelAllRequests(session);
                        if (cancelled) {
                            // Done{cancelled} is this command's single terminal frame — it resolves
                            // the Request; it is not an ack of the Cancel.
                            send(
                                    identity,
                                    new IpcFrame.Done(Map.of(IpcMeta.CANCELLED, true), null));
                        }
                        log.trace(
                                "CANC {}: cancelled in-flight request(s) (terminal sent={})",
                                identity.substring(0, 8),
                                cancelled);
                    }
                }

                case IpcFrame.Bye b -> {
                    log.trace("BYE  {}: terminal disconnecting", identity.substring(0, 8));
                    // : Bye is fire-and-forget — the client tears down without waiting, and the
                    // server closes on receipt without sending anything back (no Done). Closing is
                    // idempotent; closeSession sets closed=true so the session loop exits.
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

    // ── Pool 1 — Heartbeat loop ───────────────────────────────────────────

    /**
     * Periodically scans all active sessions and evicts any that have been silent for longer than
     * {@link #SESSION_TIMEOUT_MS}. Runs on a dedicated infrastructure platform thread.
     *
     * <p>Checking at {@link #HEARTBEAT_CHECK_MS} intervals (⅓ of the timeout) bounds the worst-case
     * eviction lag to {@code SESSION_TIMEOUT_MS + HEARTBEAT_CHECK_MS}.
     */
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
     */
    private void closeSession(@NonNull Session session) {
        if (session.closed.compareAndSet(false, true)) {
            cancelAllRequests(session);
            sessions.remove(session.identity);
            log.debug("Session closed for {}", session.identity.substring(0, 8));
        }
    }

    /**
     * Sends a command's terminal frame, claiming the session's exactly-once terminal slot first
     * (IPC interaction ). If a terminal frame was already sent for this in-flight command (e.g. a
     * cancel already sent {@code Done{cancelled}}), this send is dropped — a Request resolves with
     * exactly one terminal frame.
     *
     * @param session the session owning the in-flight command
     * @param frame the terminal frame ({@link IpcFrame.Done}/{@link IpcFrame.Error}/{@link
     *     IpcFrame.Terminate}) to send
     */
    private void sendTerminal(@NonNull Session session, @NonNull IpcFrame frame) {
        if (session.terminalSent.compareAndSet(false, true)) {
            send(session.identity, frame);
        }
    }

    /**
     * Cancels all futures in {@link Session#activeRequests} and clears the tracking set.
     *
     * <p>{@link Session#activeRequests} is a {@link ConcurrentHashMap}-backed set, so it is safe to
     * call this from any thread while request workers concurrently call {@code remove} via the
     * {@code whenComplete} self-removal callback.
     *
     * <p>Note: {@link CompletableFuture#cancel(boolean)} marks the future as cancelled but does not
     * interrupt the underlying thread. Commands that support cooperative cancellation should
     * periodically check {@link Thread#isInterrupted} and exit early.
     */
    private void cancelAllRequests(@NonNull Session session) {
        // Drain the set atomically so concurrent whenComplete callbacks don't re-add entries.
        Set<CompletableFuture<Void>> snapshot = Set.copyOf(session.activeRequests);
        session.activeRequests.removeAll(snapshot);
        for (CompletableFuture<Void> task : snapshot) {
            task.cancel(true);
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
        if (outbox.size() > MAX_OUTBOX_SIZE) {
            log.warn(
                    "Outbox congested ({} entries) — dropping {} for {}",
                    outbox.size(),
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
     * @return a new, unauthenticated {@link VetoCommandSender}; never {@code null}
     */
    private @NonNull VetoCommandSender createSender(@NonNull String identity) {
        return new VetoCommandSender(this, null, identity);
    }

    // ── Types ─────────────────────────────────────────────────────────────

    /** A frame that has been queued for sending by the IO thread. */
    public record OutboxEntry(@NonNull String identity, @NonNull IpcFrame frame) {}

    /**
     * All mutable state for a single connected terminal session.
     *
     * <p>The {@link #mailbox} is written by the IO thread and read by the session worker. The
     * {@link #activeRequests} set is written by the session worker (add) and read/mutated by
     * request workers (remove on completion) and the session worker or heartbeat thread (cancel).
     * Both structures are thread-safe by design.
     */
    static class Session {
        @NonNull final String identity;
        @NonNull final VetoCommandSender sender;

        /** Timestamp of the last received frame; read by the heartbeat thread. */
        volatile long lastActivityMillis = System.currentTimeMillis();

        /**
         * Incoming frame mailbox. Written by the IO thread via {@link #routeFrame}; consumed in
         * FIFO order by the session worker.
         */
        final BlockingQueue<IpcFrame> mailbox = new LinkedBlockingQueue<>();

        /**
         * Active request futures — added by the session worker, removed via the {@code
         * whenComplete} self-removal callback that runs on the request worker thread. Uses a {@link
         * ConcurrentHashMap}-backed set so concurrent add and remove are safe without explicit
         * locking.
         */
        final Set<CompletableFuture<Void>> activeRequests = ConcurrentHashMap.newKeySet();

        /**
         * Closed flag. Set via {@link AtomicBoolean#compareAndSet} to guarantee exactly-once
         * session teardown even when multiple threads race to close the same session.
         */
        final AtomicBoolean closed = new AtomicBoolean(false);

        /**
         * Whether a terminal frame has been sent for the in-flight command. Claimed (false→true) by
         * the dispatch task when it sends the command's terminal, and by the cancel handler when it
         * sends {@code Done{cancelled}} — whichever claims first sends the command's single
         * terminal frame (IPC interaction : exactly one terminal frame per {@link
         * IpcFrame.Request}; a cancel racing a normal completion must not produce a second). Reset
         * to {@code false} when a new {@link IpcFrame.Request} is dispatched. Server-initiated
         * {@link IpcFrame.Terminate} (timeout/shutdown) bypasses the claim — it is session
         * teardown, not a Request resolution.
         */
        final AtomicBoolean terminalSent = new AtomicBoolean(false);

        Session(@NonNull String identity, @NonNull VetoCommandSender sender) {
            this.identity = identity;
            this.sender = sender;
        }
    }
}
