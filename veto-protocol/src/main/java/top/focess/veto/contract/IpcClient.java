package top.focess.veto.contract;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZContext;

/**
 * Terminal-side IPC connection — a transport-agnostic reassembly of the prior {@code ZmqClient}.
 *
 * <p>Owns the connection lifecycle (handshake, IO loop, heartbeat, close) on top of a {@link
 * ClientTransport} (a {@link ZmqChannel.Client} for local ZMQ; a future WSS/gRPC channel for
 * remote). Seq correlation is delegated to {@link SeqCorrelator}, framing to {@link IpcCodec}, and
 * socket ownership to the transport — so this class holds only connection-level concerns.
 *
 * <h3>Thread model</h3>
 *
 * <ul>
 *   <li><b>IO thread ({@code ipc-io})</b> — sole owner of the transport. Polls for inbound frames,
 *       routes sequenced responses via the {@link SeqCorrelator} and the rest to the {@link
 *       #incomingQueue}, and drains the {@link #outbox}.
 *   <li><b>Heartbeat thread ({@code ipc-hb})</b> — enqueues a keep-alive every 30 s. Unlike the
 *       prior terminal-owned heartbeat it survives transient send errors (logs and retries rather
 *       than dying).
 *   <li><b>Caller threads</b> — {@link #send} is non-blocking (enqueues to the bounded outbox);
 *       {@link #receive}, {@link #complete}, {@link #hint} block on the incoming queue /
 *       correlator.
 * </ul>
 *
 * <h3>Improvements over {@code ZmqClient}</h3>
 *
 * <ul>
 *   <li>Seq numbers come from {@link SeqCorrelator#next} as the single source, so the handshake
 *       (seq 1) and the first user request (seq 2) no longer collide.
 *   <li>The negotiated {@link IpcFrame.Welcome} version is validated.
 *   <li>{@link #close} flushes the outbox (the final IO-loop drain sends the pending {@link
 *       IpcFrame.Bye}) before teardown, so goodbye is not lost.
 *   <li>The outbox is bounded with an explicit drop-on-full policy.
 *   <li>Connect-once semantics: a transport error logs, closes, and the connection is dead. There
 *       is no mid-session reconnect — for a security-first engine, reconciling half-sent requests
 *       and stale session state silently is unsafe. Reconnect, when needed, is a transport/tunnel
 *       concern (the channel re-handshakes) or an application concern (the terminal builds a fresh
 *       connection), never a hidden middle-layer callback.
 * </ul>
 */
public final class IpcClient implements AutoCloseable {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.contract.IpcClient");

    private static final int POLL_TIMEOUT_MS = 50;
    private static final long HEARTBEAT_INTERVAL_MS = 30_000;
    private static final long DEFAULT_RECEIVE_TIMEOUT_S = 120;
    private static final int OUTBOX_CAPACITY = 256;
    private static final long HANDSHAKE_TIMEOUT_MS = 10_000;

    /** Present only when this client owns the ZeroMQ context rather than an injected transport. */
    private final ZContext ctx;

    private final @NonNull String identity;
    private final @NonNull ClientTransport transport;
    private final @NonNull SeqCorrelator correlator = new SeqCorrelator();
    private final boolean ownsContext;

    /** Outbound frames waiting for the IO thread. Bounded; drop-on-full with a warning. */
    private final @NonNull BlockingQueue<IpcFrame.@NonNull ClientFrame> outbox =
            new ArrayBlockingQueue<>(OUTBOX_CAPACITY);

    /** Inbound non-sequenced frames (Delta/Progress/Prompt/Done/Error/Terminate) for the caller. */
    private final @NonNull BlockingQueue<IpcFrame.@NonNull ServerFrame> incomingQueue =
            new LinkedBlockingQueue<>();

    private Thread ioThread;
    private Thread heartbeatThread;

    private volatile boolean closed;
    private volatile int negotiatedVersion = IpcFrame.PROTOCOL_VERSION;

    /** The product version this client reports in the {@link IpcFrame.Hello} handshake. */
    private final @NonNull Version productVersion;

    /**
     * The current working directory this client reports in the {@link IpcFrame.Hello} handshake, so
     * the server can map it to the session's workspace. Defaults to the JVM working dir, so it is
     * never {@code null}.
     */
    private final @NonNull String cwd;

    /** The backend's product version received in {@link IpcFrame.Welcome}. */
    private volatile @NonNull Version serverProductVersion = Version.UNKNOWN;

    // ── construction ───────────────────────────────────────────────────────

    /**
     * Connects to the backend, performs the handshake, and starts the IO and heartbeat threads.
     * Equivalent to {@link #IpcClient(String, Version, String) IpcClient(address, Version.UNKNOWN,
     * System.getProperty("user.dir"))} - the client reports {@link Version#UNKNOWN} and the JVM
     * working dir as cwd.
     *
     * @param address the transport connect address (e.g. {@code tcp://127.0.0.1:5555})
     * @throws RuntimeException if the handshake times out or is rejected
     */
    public IpcClient(@NonNull String address) {
        this(address, Version.UNKNOWN, System.getProperty("user.dir"));
    }

    /**
     * Connects to the backend, performs the handshake, and starts the IO and heartbeat threads.
     * Equivalent to {@link #IpcClient(String, Version, String) IpcClient(address, productVersion,
     * System.getProperty("user.dir"))} - the client reports the JVM working dir as cwd.
     *
     * @param address the transport connect address (e.g. {@code tcp://127.0.0.1:5555})
     * @param productVersion the product version to report in the {@link IpcFrame.Hello} handshake;
     *     never {@code null} - use {@link Version#UNKNOWN} when none is known
     * @throws RuntimeException if the handshake times out or is rejected
     */
    public IpcClient(@NonNull String address, @NonNull Version productVersion) {
        this(address, productVersion, System.getProperty("user.dir"));
    }

    /**
     * Connects to the backend, performs the handshake, and starts the IO and heartbeat threads.
     *
     * @param address the transport connect address (e.g. {@code tcp://127.0.0.1:5555})
     * @param productVersion the product version to report in the {@link IpcFrame.Hello} handshake;
     *     never {@code null} - use {@link Version#UNKNOWN} when none is known
     * @param cwd the terminal's current working directory to report in the {@link IpcFrame.Hello}
     *     handshake, mapped to the session's workspace at {@code /session create} time; never
     *     {@code null} - the terminal always has a cwd to report
     * @throws RuntimeException if the handshake times out or is rejected
     */
    public IpcClient(
            @NonNull String address, @NonNull Version productVersion, @NonNull String cwd) {
        this.identity = UUID.randomUUID().toString();
        this.ctx = new ZContext();
        this.ownsContext = true;
        this.transport = ZmqChannel.Client.connectDealer(ctx, address, identity);
        this.productVersion = productVersion;
        this.cwd = cwd;
        start();
    }

    /**
     * Constructor for testing with an injected transport (e.g. an in-memory {@link
     * ClientTransport}), bypassing ZMQ entirely. The connection does not own a {@link ZContext}.
     * Equivalent to {@link #IpcClient(ClientTransport, Version, String) IpcClient(transport,
     * Version.UNKNOWN, System.getProperty("user.dir"))}.
     *
     * <p>Public so a reusable in-memory transport in a downstream client module can drive a
     * connection without ZMQ.
     *
     * @param transport the transport to drive
     */
    public IpcClient(@NonNull ClientTransport transport) {
        this(transport, Version.UNKNOWN, System.getProperty("user.dir"));
    }

    /**
     * Constructor for testing with an injected transport (e.g. an in-memory {@link
     * ClientTransport}), bypassing ZMQ entirely. The connection does not own a {@link ZContext}.
     * Equivalent to {@link #IpcClient(ClientTransport, Version, String) IpcClient(transport,
     * Version.UNKNOWN, System.getProperty("user.dir"))}.
     *
     * <p>Public so a reusable in-memory transport in a downstream client module can drive a
     * connection without ZMQ.
     *
     * @param transport the transport to drive
     * @param productVersion the product version to report in the {@link IpcFrame.Hello} handshake;
     *     never {@code null} - use {@link Version#UNKNOWN} when none is known
     */
    public IpcClient(@NonNull ClientTransport transport, @NonNull Version productVersion) {
        this(transport, productVersion, System.getProperty("user.dir"));
    }

    /**
     * Constructor for testing with an injected transport (e.g. an in-memory {@link
     * ClientTransport}), bypassing ZMQ entirely. The connection does not own a {@link ZContext}.
     *
     * <p>Public so a reusable in-memory transport in a downstream client module can drive a
     * connection without ZMQ.
     *
     * @param transport the transport to drive
     * @param productVersion the product version to report in the {@link IpcFrame.Hello} handshake;
     *     never {@code null} - use {@link Version#UNKNOWN} when none is known
     * @param cwd the terminal's current working directory to report in the {@link IpcFrame.Hello}
     *     handshake; never {@code null} - pass {@code System.getProperty("user.dir")} when the test
     *     has no specific cwd
     */
    public IpcClient(
            @NonNull ClientTransport transport,
            @NonNull Version productVersion,
            @NonNull String cwd) {
        this.identity = UUID.randomUUID().toString();
        this.ctx = null;
        this.ownsContext = false;
        this.transport = transport;
        this.productVersion = productVersion;
        this.cwd = cwd;
        start();
    }

    private void start() {
        handshake();
        Thread io = new Thread(this::ioLoop, "ipc-io");
        this.ioThread = io;
        io.setDaemon(true);
        io.start();

        Thread heartbeat = new Thread(this::heartbeatLoop, "ipc-hb");
        this.heartbeatThread = heartbeat;
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    // ── handshake ─────────────────────────────────────────────────────────

    /**
     * Sends a {@link IpcFrame.Hello} and awaits the matching {@link IpcFrame.Welcome}, validating
     * the negotiated version. Runs on the constructor thread before the IO loop starts.
     */
    private void handshake() {
        long seq = correlator.next();
        transport.send(new IpcFrame.Hello(IpcFrame.PROTOCOL_VERSION, seq, productVersion, cwd));

        long deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new RuntimeException("Handshake timed out — backend may be incompatible");
            }
            Transport.FramedMsg msg = transport.recv(remaining);
            if (msg == null) continue;
            IpcFrame frame = msg.frame();
            switch (frame) {
                case IpcFrame.Welcome(int version, long seq1, Version pv) when seq1 == seq -> {
                    if (version < 1) {
                        throw new RuntimeException(
                                "Backend negotiated unsupported protocol version " + version);
                    }
                    negotiatedVersion = version;
                    serverProductVersion = pv;
                    return;
                }
                case IpcFrame.Error e ->
                        throw new RuntimeException("Backend rejected handshake: " + e.content());

                // An unrelated frame arrived during handshake — protocol violation.
                case IpcFrame.Terminate t ->
                        throw new RuntimeException(
                                "Backend terminated during handshake: " + t.reason());

                case IpcFrame.ServerFrame serverFrame ->
                        log.warn(
                                "Unexpected {} frame during handshake — discarding (protocol violation)",
                                frame.getClass().getSimpleName());
                default -> {}
            }
        }
    }

    // ── IO loop ───────────────────────────────────────────────────────────

    private void ioLoop() {
        try {
            while (!closed) {
                drainOutbox();
                // Non-blocking recv when there is pending outbound work, so sends are not delayed;
                // otherwise poll briefly.
                long timeout = outbox.isEmpty() ? POLL_TIMEOUT_MS : 0;
                Transport.FramedMsg msg = transport.recv(timeout);
                if (msg != null && msg.frame() instanceof IpcFrame.ServerFrame sf) {
                    route(sf);
                }
            }
            // Final drain so a pending Bye (and anything else) is flushed before teardown.
            drainOutbox();
        } catch (Throwable t) {
            // Connect-once: a transport error is fatal. Log it, mark closed, and let the finally
            // close the transport. Callers observe this via isClosed / receive returning null.
            // (No silent mid-session reconnect: re-handshake belongs in the channel/tunnel; a fresh
            // session belongs to the application.)
            if (!closed) {
                log.error("IO thread error — connection closing", t);
                closed = true;
            }
        } finally {
            transport.close();
        }
    }

    /** Routes an inbound server frame to the correlator (sequenced) or the incoming queue. */
    private void route(IpcFrame.@NonNull ServerFrame frame) {
        if (frame instanceof IpcFrame.SeqResponse sr && sr.seq() != 0) {
            correlator.deliver(sr);
            return;
        }
        // Non-sequenced frames, and seq=0 responses (e.g. a streaming Error), reach the caller.
        if (!incomingQueue.offer(frame)) {
            log.warn("Incoming queue full — dropping {}", frame.getClass().getSimpleName());
        }
    }

    /** Sends every queued outbound frame; never throws — logs send failures and continues. */
    private void drainOutbox() {
        IpcFrame.ClientFrame frame;
        while ((frame = outbox.poll()) != null) {
            try {
                transport.send(frame);
            } catch (Throwable t) {
                log.warn("Send failed for {}", frame.getClass().getSimpleName(), t);
            }
        }
    }

    // ── heartbeat ─────────────────────────────────────────────────────────

    @SuppressWarnings("BusyWait") // Deliberate fixed-rate heartbeat sender, not a spin loop.
    private void heartbeatLoop() {
        while (!closed) {
            try {
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (closed) return;
            try {
                send(new IpcFrame.Heartbeat());
            } catch (Exception e) {
                // Transient send errors must not kill the heartbeat — log and retry next interval.
                if (!closed) log.warn("Heartbeat send failed (will retry)", e);
            }
        }
    }

    // ── send ──────────────────────────────────────────────────────────────

    /**
     * Enqueues a client frame for asynchronous send. For sequenced requests the response handler is
     * registered before the frame is queued, so a fast response is never missed.
     *
     * <p>No closed-check: it would be a TOCTOU with no lock to make it sound (another thread can
     * close between the check and the enqueue), so it would only narrow the race window while
     * implying a guarantee that doesn't hold. The contract is single-ownership — the owner stops
     * using the connection before closing it. Best-effort status is available via {@link
     * #isClosed}.
     *
     * @param frame the client frame to send
     */
    public void send(IpcFrame.@NonNull ClientFrame frame) {
        if (frame instanceof IpcFrame.SeqRequest sr) {
            correlator.register(sr.seq());
        }
        if (!outbox.offer(frame)) {
            if (frame instanceof IpcFrame.SeqRequest sr) {
                correlator.discard(sr.seq());
            }
            log.warn(
                    "Outbox full ({} entries) — dropping {}",
                    OUTBOX_CAPACITY,
                    frame.getClass().getSimpleName());
        }
    }

    // ── receive ───────────────────────────────────────────────────────────

    /**
     * Blocks up to {@value #DEFAULT_RECEIVE_TIMEOUT_S} s for the next non-sequenced server frame.
     *
     * @return the next server frame, or {@code null} if timed out
     * @throws InterruptedException if the calling thread is interrupted
     */
    public IpcFrame.ServerFrame receive() throws InterruptedException {
        return incomingQueue.poll(DEFAULT_RECEIVE_TIMEOUT_S, TimeUnit.SECONDS);
    }

    /**
     * Blocks up to {@code timeout} for the next non-sequenced server frame.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit
     * @return the next server frame, or {@code null} if timed out
     * @throws InterruptedException if the calling thread is interrupted
     */
    public IpcFrame.ServerFrame receive(long timeout, @NonNull TimeUnit unit)
            throws InterruptedException {
        return incomingQueue.poll(timeout, unit);
    }

    // ── complete ──────────────────────────────────────────────────────────

    /**
     * Sends a tab-completion request and blocks for the candidates.
     *
     * @param line the command line prefix
     * @param timeout the maximum time to wait
     * @param unit the time unit
     * @return the completion result, or {@code null} on timeout or error response
     */
    public IpcFrame.CompleteResult complete(
            @NonNull String line, long timeout, @NonNull TimeUnit unit) {
        long seq = correlator.next();
        send(new IpcFrame.Complete(line, seq));
        try {
            IpcFrame.SeqResponse reply = correlator.await(seq, timeout, unit);
            if (reply instanceof IpcFrame.CompleteResult cr) return cr;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    // ── hint ──────────────────────────────────────────────────────────────

    /**
     * Sends an argument-hint request and blocks for the hint.
     *
     * @param line the current command line
     * @param timeout the maximum time to wait
     * @param unit the time unit
     * @return the hint result, or {@code null} on timeout or error response
     */
    public IpcFrame.HintResult hint(@NonNull String line, long timeout, @NonNull TimeUnit unit) {
        long seq = correlator.next();
        send(new IpcFrame.Hint(line, seq));
        try {
            IpcFrame.SeqResponse reply = correlator.await(seq, timeout, unit);
            if (reply instanceof IpcFrame.HintResult hr) return hr;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    // ── lifecycle ─────────────────────────────────────────────────────────

    /**
     * Gracefully shuts down: enqueues a {@link IpcFrame.Bye}, signals the loops to stop, and waits
     * for the IO thread to flush the outbox and close the transport before releasing the context.
     *
     * <p>Called from the owning (application) thread, never the IO thread — the IO loop's {@code
     * finally} closes the transport, not this. If that invariant is ever broken and the IO thread
     * does reach here, the {@code ioThread.join} self-joins (times out after 2 s), {@code
     * ctx.close} runs, and the resumed loop's final drain logs a send failure — surfaced, not
     * silent. No guard for it: it guards an unreachable path, and a guard here would be the same
     * speculative defense we removed elsewhere.
     */
    @Override
    public void close() {
        if (closed) return;
        // Enqueue Bye so the IO loop's final drain flushes it before teardown.
        if (!outbox.offer(new IpcFrame.Bye())) {
            log.warn("Outbox full during close — Bye frame dropped");
        }
        closed = true;
        Thread heartbeat = heartbeatThread;
        if (heartbeat != null) {
            heartbeat.interrupt();
        }
        Thread io = ioThread;
        if (io != null) {
            try {
                io.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (ownsContext && ctx != null) {
            ctx.close();
        }
    }

    /** The negotiated protocol version from the handshake. */
    public int negotiatedVersion() {
        return negotiatedVersion;
    }

    /**
     * The backend's product version received in the {@link IpcFrame.Welcome} handshake; never
     * {@code null} - {@link Version#UNKNOWN} when the backend did not report a meaningful version.
     */
    public @NonNull Version serverProductVersion() {
        return serverProductVersion;
    }

    /** The unique UUID identity of this connection. */
    public @NonNull String identity() {
        return identity;
    }

    /** True if the connection has been closed. */
    public boolean isClosed() {
        return closed;
    }
}
