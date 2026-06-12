package top.focess.veto.terminal;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.ZmqTransport;

/**
 * Terminal-side ZeroMQ transport — a DEALER socket connected to the backend ROUTER.
 *
 * <p>Each terminal generates a UUID identity on startup. The DEALER socket sends {@link IpcFrame}
 * messages as JSON and receives responses routed back by identity.
 *
 * <h3>Thread model</h3>
 *
 * <ul>
 *   <li><b>IO thread</b> — owns the ZMQ socket exclusively. Polls with a 50 ms timeout, drains the
 *       {@link #outbox} and writes to the socket, reads incoming frames and routes them to the
 *       correct per-sequence queue in {@link #seqHandlers} or the general {@link #incomingQueue}.
 *       This is the same single-IO-thread pattern used by {@code ZmqServer}.
 *   <li><b>Caller threads</b> — {@link #send(IpcFrame.ClientFrame)} is non-blocking (just enqueues
 *       to the outbox). It automatically registers a per-sequence response queue if the frame is a
 *       sequenced request, so the caller can drain response frames.
 *   <li><b>Heartbeat thread</b> — periodically enqueues keep-alive frames via {@link #send}.
 * </ul>
 *
 * <h3>Response routing</h3>
 *
 * <ul>
 *   <li>{@link IpcFrame.SeqResponse} frames with a non-zero sequence number are routed to the queue
 *       registered under that sequence number.
 *   <li>Frames without a sequence number ({@link IpcFrame.Delta}, {@link IpcFrame.Progress}, {@link
 *       IpcFrame.Prompt}, {@link IpcFrame.Done}) are routed to the incoming queue.
 * </ul>
 *
 * <h3>Thread safety</h3>
 *
 * The IO thread is the only thread that touches the ZMQ socket. All other threads interact via
 * thread-safe collections ({@link ConcurrentLinkedQueue}, {@link LinkedBlockingQueue}). No explicit
 * locking is needed.
 */
public final class ZmqClient implements AutoCloseable {

    private static final Logger log = Logger.getLogger(ZmqClient.class.getName());

    private static final int POLL_TIMEOUT_MS = 50;

    private final ZContext ctx;
    private final ZmqTransport transport;
    private final String identity;
    private final Thread ioThread;

    /** Outbound frames waiting to be sent by the IO thread. */
    private final ConcurrentLinkedQueue<IpcFrame.ClientFrame> outbox =
            new ConcurrentLinkedQueue<>();

    /** Incoming frames waiting to be received by the caller. */
    private final BlockingQueue<IpcFrame.ServerFrame> incomingQueue = new LinkedBlockingQueue<>();

    /** Active handlers for seq-based responses. */
    private final ConcurrentHashMap<Long, BlockingQueue<IpcFrame.SeqResponse>> seqHandlers =
            new ConcurrentHashMap<>();

    private final AtomicLong nextSeq = new AtomicLong(1);

    private volatile boolean closed;

    // ── construction ────────────────────────────────────────────────────────

    /**
     * Connects to the backend ROUTER socket, executes the connection handshake synchronously,
     * and spawns the daemon background IO loop thread.
     *
     * @param address ZMQ connect address (e.g. {@code tcp://127.0.0.1:5555})
     * @throws RuntimeException if the handshake times out or is rejected by the server
     */
    public ZmqClient(@NotNull String address) {
        this.identity = UUID.randomUUID().toString();
        this.ctx = new ZContext();
        // Setup DEALER socket connection using our unique identity.
        this.transport = ZmqTransport.connectDealer(ctx, address, identity);
        System.out.println("Connecting to backend at " + address + " ...");
        handshake();
        System.out.println("Connected.");
        // We run ZMQ socket I/O in a dedicated thread to ensure thread-safety of ZeroMQ resources.
        this.ioThread = new Thread(this::ioLoop, "zmq-io");
        this.ioThread.setDaemon(true);
        this.ioThread.start();
    }

    /**
     * Performs connection protocol validation by sending a Hello frame and awaiting a Welcome frame.
     * Runs in the caller thread during construction before the socket loop begins.
     *
     * @throws RuntimeException if the connection is rejected or timed out
     */
    private void handshake() {
        try {
            transport.send(new IpcFrame.Hello(IpcFrame.PROTOCOL_VERSION, 1));
        } catch (Exception e) {
            throw new RuntimeException("Failed to send handshake Hello", e);
        }

        // Temporarily set a 10-second timeout on receive for the handshake phase.
        transport.socket.setReceiveTimeOut(10_000);

        try {
            while (true) {
                ZmqTransport.ZmqMessage msg = transport.receive();
                if (msg == null) {
                    throw new RuntimeException("Handshake timed out — backend may be incompatible");
                }
                IpcFrame frame = msg.frame();
                if (frame instanceof IpcFrame.Welcome w && w.seq() == 1) {
                    return;
                }
                if (frame instanceof IpcFrame.Error e) {
                    throw new RuntimeException("Backend rejected handshake: " + e.content());
                }
                // Keep waiting if we receive unrelated handshake frames.
            }
        } finally {
            // Restore socket to standard non-blocking mode.
            transport.socket.setReceiveTimeOut(-1);
        }
    }

    // ── IO loop ─────────────────────────────────────────────────────────────

    /**
     * dedicated background event loop running in the "zmq-io" thread.
     * Handles polling the ZMQ socket for inbound messages, drafting and executing sends from the outbox.
     */
    private void ioLoop() {
        ZMQ.Poller poller = null;
        try {
            poller = ctx.createPoller(1);
            poller.register(transport.socket, ZMQ.Poller.POLLIN);

            while (!closed) {
                // Poll with 50ms timeout to avoid busy-waiting.
                int active = poller.poll(POLL_TIMEOUT_MS);

                // 1. Send all queued outbound messages.
                IpcFrame.ClientFrame frame;
                while ((frame = outbox.poll()) != null) {
                    try {
                        transport.send(frame);
                    } catch (Exception e) {
                        log.log(Level.WARNING, "Send failed for " + frame.getClass().getSimpleName(), e);
                    }
                }

                // 2. Read incoming frames if data is ready.
                if (active > 0 && poller.pollin(0)) {
                    ZmqTransport.ZmqMessage msg = transport.tryReceive();
                    if (msg != null && msg.frame() instanceof IpcFrame.ServerFrame sf) {
                        route(sf);
                    }
                }
            }
        } catch (Exception e) {
            if (!closed) {
                log.log(Level.SEVERE, "IO thread error", e);
                closed = true;
            }
        } finally {
            // Clean up sockets/poller on shutdown.
            if (poller != null) {
                try {
                    poller.close();
                } catch (Exception ignored) {
                }
            }
            try {
                transport.close();
            } catch (Exception ignored) {
            }
        }
    }


    /**
     * Routes an incoming server frame either to a sequence-specific waiting queue or the main REPL input queue.
     *
     * @param frame the incoming server frame
     */
    private void route(@NotNull IpcFrame.ServerFrame frame) {
        if (frame instanceof IpcFrame.SeqResponse sr) {
            if (sr.seq() != 0) {
                BlockingQueue<IpcFrame.SeqResponse> handler = seqHandlers.get(sr.seq());
                if (handler != null) {
                    handler.offer(sr);
                } else {
                    log.fine("No handler registered for seq=" + sr.seq());
                }
                return;
            }
        }

        // All general frames (Deltas, Done, etc.) go to the general incoming queue.
        incomingQueue.offer(frame);
    }

    // ── send ────────────────────────────────────────────────────────────────

    /**
     * Enqueues a client frame to the outbox queue to be sent asynchronously by the IO thread.
     *
     * @param frame the client frame to send
     * @throws IllegalStateException if the ZmqClient is closed
     */
    public void send(@NotNull IpcFrame.ClientFrame frame) {
        if (closed) throw new IllegalStateException("Client is closed");
        if (frame instanceof IpcFrame.SeqRequest sr) {
            // Pre-register response queue to catch responses before the packet is sent.
            seqHandlers.put(sr.seq(), new LinkedBlockingQueue<>(1));
        }
        outbox.offer(frame);
    }

    // ── receive ─────────────────────────────────────────────────────────────

    /**
     * Blocks up to 120 seconds for the next server frame on the general incoming queue.
     *
     * @return the next server frame, or null if timed out
     * @throws InterruptedException if the calling thread is interrupted
     */
    @Nullable
    public IpcFrame.ServerFrame receive() throws InterruptedException {
        return receive(0);
    }

    /**
     * Blocks up to 120 seconds for a specific response matching the given sequence number.
     *
     * @param seq the sequence number of the expected response
     * @return the sequence response, or null if timed out
     * @throws InterruptedException if the calling thread is interrupted
     */
    @Nullable
    public IpcFrame.ServerFrame receive(long seq) throws InterruptedException {
        return receive(seq, 120, TimeUnit.SECONDS);
    }

    /**
     * Blocks for a general server frame with a custom timeout.
     *
     * @param timeout the timeout duration
     * @param unit the time unit
     * @return the next server frame, or null if timed out
     * @throws InterruptedException if the calling thread is interrupted
     */
    @Nullable
    public IpcFrame.ServerFrame receive(long timeout, @NotNull TimeUnit unit)
            throws InterruptedException {
        return receive(0, timeout, unit);
    }

    /**
     * Blocks for a sequence-specific or general server frame with a custom timeout.
     * Removes the sequence handler mapping once the wait is finished.
     *
     * @param seq the sequence number, or 0 for general queue
     * @param timeout the timeout duration
     * @param unit the time unit
     * @return the server frame, or null if timed out
     * @throws InterruptedException if the calling thread is interrupted
     */
    @Nullable
    public IpcFrame.ServerFrame receive(long seq, long timeout, @NotNull TimeUnit unit)
            throws InterruptedException {
        if (seq != 0) {
            BlockingQueue<IpcFrame.SeqResponse> queue = seqHandlers.get(seq);
            if (queue == null) {
                return null;
            }
            try {
                return queue.poll(timeout, unit);
            } finally {
                // Ensure handler registry is cleaned up to prevent memory leaks.
                seqHandlers.remove(seq);
            }
        } else {
            return incomingQueue.poll(timeout, unit);
        }
    }

    // ── complete ────────────────────────────────────────────────────────────

    /**
     * Sends a command completion request and blocks synchronously waiting for the candidates.
     *
     * @param line the command line prefix input
     * @param timeout the maximum time to wait
     * @param unit the time unit
     * @return the result candidates, or null on timeout/interruption
     */
    @Nullable
    public IpcFrame.CompleteResult complete(
            @NotNull String line, long timeout, @NotNull TimeUnit unit) {
        if (closed) return null;
        long seq = nextSeq.getAndIncrement();
        send(new IpcFrame.Complete(line, seq));
        try {
            IpcFrame.ServerFrame reply = receive(seq, timeout, unit);
            if (reply instanceof IpcFrame.CompleteResult cr) {
                return cr;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    // ── hint ────────────────────────────────────────────────────────────────

    /**
     * Sends a parameter autocomplete hint request and blocks synchronously waiting for the display text.
     *
     * @param line the current command line input
     * @param timeout the maximum time to wait
     * @param unit the time unit
     * @return the hint result containing suggested arguments, or null on timeout/interruption
     */
    @Nullable
    public IpcFrame.HintResult hint(@NotNull String line, long timeout, @NotNull TimeUnit unit) {
        if (closed) return null;
        long seq = nextSeq.getAndIncrement();
        send(new IpcFrame.Hint(line, seq));
        try {
            IpcFrame.ServerFrame reply = receive(seq, timeout, unit);
            if (reply instanceof IpcFrame.HintResult hr) {
                return hr;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    /**
     * Gracefully shuts down the ZeroMQ client. Shuts down the background I/O thread,
     * closes the transport connections, and releases ZContext resources.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;

        // Wait for the background IO thread to exit to prevent thread leaks.
        if (Thread.currentThread() != ioThread) {
            try {
                ioThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        ctx.close();
    }

    /**
     * Returns the unique UUID identity generated for this terminal instance.
     *
     * @return the UUID string
     */
    @NotNull
    public String identity() {
        return identity;
    }
}
