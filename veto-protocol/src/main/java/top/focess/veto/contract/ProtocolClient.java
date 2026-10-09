package top.focess.veto.contract;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns handshake, queues, IO, heartbeat and response correlation over any ClientTransport. */
public final class ProtocolClient implements AutoCloseable {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.contract.ProtocolClient");

    private static final int POLL_TIMEOUT_MS = 50;
    private final long heartbeatIntervalMillis;
    private static final long DEFAULT_RECEIVE_TIMEOUT_S = 120;
    private static final int OUTBOX_CAPACITY = 256;
    private static final long HANDSHAKE_TIMEOUT_MS = 10_000;

    private final @NonNull ClientTransport transport;
    private final @NonNull SeqCorrelator correlator = new SeqCorrelator();

    /** Outbound frames waiting for the IO thread. Bounded; drop-on-full with a warning. */
    private final @NonNull BlockingQueue<Frame.@NonNull ClientFrame> outbox =
            new ArrayBlockingQueue<>(OUTBOX_CAPACITY);

    /** Inbound non-sequenced frames (Delta/Progress/Prompt/Done/Error/Terminate) for the caller. */
    private final @NonNull BlockingQueue<Frame.@NonNull ServerFrame> incomingQueue =
            new LinkedBlockingQueue<>();

    private Thread ioThread;
    private Thread heartbeatThread;

    private volatile boolean closed;
    private volatile int negotiatedVersion = Frame.PROTOCOL_VERSION;

    /** The product version this client reports in the {@link Frame.Hello} handshake. */
    private final @NonNull Version productVersion;

    /**
     * The current working directory this client reports in the {@link Frame.Hello} handshake, so
     * the server can map it to the session's workspace. Defaults to the JVM working dir, so it is
     * never {@code null}.
     */
    private final @NonNull String cwd;

    /** The backend's product version received in {@link Frame.Welcome}. */
    private volatile @NonNull Version serverProductVersion = Version.UNKNOWN;

    public ProtocolClient(@NonNull ClientTransport transport) {
        this(transport, Version.UNKNOWN, System.getProperty("user.dir"), 30_000);
    }

    public ProtocolClient(
            @NonNull ClientTransport transport,
            @NonNull Version productVersion,
            @NonNull String cwd) {
        this(transport, productVersion, cwd, 30_000);
    }

    public ProtocolClient(
            @NonNull ClientTransport transport,
            @NonNull Version productVersion,
            @NonNull String cwd,
            long heartbeatIntervalMillis) {
        if (heartbeatIntervalMillis <= 0)
            throw new IllegalArgumentException("Heartbeat interval must be positive");
        this.transport = transport;
        this.productVersion = productVersion;
        this.cwd = cwd;
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        start();
    }

    private void start() {
        try {
            handshake();
        } catch (RuntimeException | Error failure) {
            closed = true;
            try {
                closeTransport();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        Thread io = new Thread(this::ioLoop, "protocol-io");
        this.ioThread = io;
        io.setDaemon(true);
        io.start();

        Thread heartbeat = new Thread(this::heartbeatLoop, "protocol-hb");
        this.heartbeatThread = heartbeat;
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    // ── handshake ─────────────────────────────────────────────────────────

    /**
     * Sends a {@link Frame.Hello} and awaits the matching {@link Frame.Welcome}, validating the
     * negotiated version. Runs on the constructor thread before the IO loop starts.
     */
    private void handshake() {
        long seq = correlator.next();
        transport.send(new Frame.Hello(Frame.PROTOCOL_VERSION, seq, productVersion, cwd));

        long deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS;
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new RuntimeException("Handshake timed out — backend may be incompatible");
            }
            Transport.FramedMsg msg = transport.recv(remaining);
            if (msg == null) continue;
            Frame frame = msg.frame();
            switch (frame) {
                case Frame.Welcome(int version, long seq1, Version pv) when seq1 == seq -> {
                    if (version != Frame.PROTOCOL_VERSION) {
                        throw new RuntimeException(
                                "Backend negotiated unsupported protocol version " + version);
                    }
                    negotiatedVersion = version;
                    serverProductVersion = pv;
                    return;
                }
                case Frame.Welcome greeting when greeting.seq() == 0 -> {
                    if (greeting.version() != Frame.PROTOCOL_VERSION)
                        throw new IllegalStateException("Unsupported greeting version");
                }
                case Frame.Error e ->
                        throw new RuntimeException("Backend rejected handshake: " + e.content());

                // An unrelated frame arrived during handshake — protocol violation.
                case Frame.Terminate t ->
                        throw new RuntimeException(
                                "Backend terminated during handshake: " + t.reason());

                case Frame.ServerFrame serverFrame -> route(serverFrame);
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
                if (msg != null && msg.frame() instanceof Frame.ServerFrame sf) {
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
            closed = true;
            Thread heartbeat = heartbeatThread;
            if (heartbeat != null) heartbeat.interrupt();
            closeTransport();
        }
    }

    /** Called by the transport owner: constructor during handshake, then the IO thread. */
    private void closeTransport() {
        transport.close();
    }

    /** Routes an inbound server frame to the correlator (sequenced) or the incoming queue. */
    private void route(Frame.@NonNull ServerFrame frame) {
        if (frame instanceof Frame.HeartbeatAck) return;
        if (frame instanceof Frame.SeqResponse sr && sr.seq() != 0) {
            correlator.deliver(sr);
            return;
        }
        // Non-sequenced frames, and seq=0 responses (e.g. a streaming Error), reach the caller.
        if (!incomingQueue.offer(frame)) {
            log.warn("Incoming queue full — dropping {}", frame.getClass().getSimpleName());
        }
    }

    /** Send failures terminate the connection through the IO loop's cleanup path. */
    private void drainOutbox() {
        Frame.ClientFrame frame;
        while ((frame = outbox.poll()) != null) transport.send(frame);
    }

    // ── heartbeat ─────────────────────────────────────────────────────────

    @SuppressWarnings("BusyWait") // Deliberate fixed-rate heartbeat sender, not a spin loop.
    private void heartbeatLoop() {
        while (!closed) {
            try {
                Thread.sleep(heartbeatIntervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (closed) return;
            try {
                send(new Frame.Heartbeat(0));
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
    public void send(Frame.@NonNull ClientFrame frame) {
        if (frame instanceof Frame.SeqRequest sr) {
            correlator.register(sr.seq());
        }
        if (!outbox.offer(frame)) {
            if (frame instanceof Frame.SeqRequest sr) {
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
    public Frame.ServerFrame receive() throws InterruptedException {
        return receive(DEFAULT_RECEIVE_TIMEOUT_S, TimeUnit.SECONDS);
    }

    /**
     * Blocks up to {@code timeout} for the next non-sequenced server frame.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit
     * @return the next server frame, or {@code null} if timed out
     * @throws InterruptedException if the calling thread is interrupted
     */
    public Frame.ServerFrame receive(long timeout, @NonNull TimeUnit unit)
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
    public Frame.CompleteResult complete(
            @NonNull String line, long timeout, @NonNull TimeUnit unit) {
        long seq = correlator.next();
        send(new Frame.Complete(line, seq));
        try {
            Frame.SeqResponse reply = correlator.await(seq, timeout, unit);
            if (reply instanceof Frame.CompleteResult cr) return cr;
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
    public Frame.HintResult hint(@NonNull String line, long timeout, @NonNull TimeUnit unit) {
        long seq = correlator.next();
        send(new Frame.Hint(line, seq));
        try {
            Frame.SeqResponse reply = correlator.await(seq, timeout, unit);
            if (reply instanceof Frame.HintResult hr) return hr;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    // ── lifecycle ─────────────────────────────────────────────────────────

    /**
     * Gracefully shuts down: enqueues a {@link Frame.Bye}, signals the loops to stop, and waits for
     * the IO thread to flush the outbox and close the transport before releasing the context.
     *
     * <p>Called from the owning application thread. The IO loop's {@code finally} closes both the
     * transport and its owned context, including when IO fails before this method is called.
     */
    @Override
    public void close() {
        if (closed) return;
        // Enqueue Bye so the IO loop's final drain flushes it before teardown.
        if (!outbox.offer(new Frame.Bye())) {
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
    }

    /** The negotiated protocol version from the handshake. */
    public int negotiatedVersion() {
        return negotiatedVersion;
    }

    /**
     * The backend's product version received in the {@link Frame.Welcome} handshake; never {@code
     * null} - {@link Version#UNKNOWN} when the backend did not report a meaningful version.
     */
    public @NonNull Version serverProductVersion() {
        return serverProductVersion;
    }

    /** True if the connection has been closed. */
    public boolean isClosed() {
        return closed;
    }
}
