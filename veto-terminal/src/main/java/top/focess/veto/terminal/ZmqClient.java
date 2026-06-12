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
     * Connect to the backend, perform the protocol handshake, and start the IO thread.
     *
     * @param address ZMQ connect address (e.g. {@code tcp://127.0.0.1:5555})
     * @throws RuntimeException if the handshake times out or is rejected
     */
    public ZmqClient(@NotNull String address) {
        this.identity = UUID.randomUUID().toString();
        this.ctx = new ZContext();
        this.transport = ZmqTransport.connectDealer(ctx, address, identity);
        log.info("Connecting to backend at " + address + " ...");
        handshake();
        log.info("Connected.");
        this.ioThread = new Thread(this::ioLoop, "zmq-io");
        this.ioThread.setDaemon(true);
        this.ioThread.start();
    }

    /**
     * Send a {@link IpcFrame.Hello} and block until the backend responds with a {@link
     * IpcFrame.Welcome}.
     *
     * <p>Called from the constructor before the IO thread starts, so we can safely use the blocking
     * {@code receive()} with a socket timeout.
     */
    private void handshake() {
        try {
            transport.send(new IpcFrame.Hello(IpcFrame.PROTOCOL_VERSION, 1));
        } catch (Exception e) {
            throw new RuntimeException("Failed to send handshake Hello", e);
        }

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
                // Unknown frame — keep waiting
            }
        } finally {
            transport.socket.setReceiveTimeOut(-1);
        }
    }

    // ── IO loop ─────────────────────────────────────────────────────────────

    /**
     * Single-threaded event loop that owns the ZMQ socket. Polls for incoming data, drains the
     * outbox, and routes responses to the correct per-sequence queue.
     */
    private void ioLoop() {
        ZMQ.Poller poller = null;
        try {
            poller = ctx.createPoller(1);
            poller.register(transport.socket, ZMQ.Poller.POLLIN);

            while (!closed) {
                int active = poller.poll(POLL_TIMEOUT_MS);

                // 1. Drain outbox — send all pending frames
                drainOutbox();

                // 2. Read incoming if any events are pending on our socket
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

    /** Send all pending outbound frames. Called exclusively by the IO thread. */
    private void drainOutbox() {
        IpcFrame.ClientFrame frame;
        while ((frame = outbox.poll()) != null) {
            try {
                transport.send(frame);
            } catch (Exception e) {
                log.log(Level.WARNING, "Send failed for " + frame.getClass().getSimpleName(), e);
            }
        }
    }

    /** Route an incoming frame to the correct response queue. */
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

        // All other responses go to the incoming queue
        incomingQueue.offer(frame);
    }

    // ── send ────────────────────────────────────────────────────────────────

    /**
     * Enqueue a frame for sending. Non-blocking — the IO thread will pick it up on the next poll
     * cycle. If the frame is a {@link IpcFrame.SeqRequest}, its response queue is automatically
     * registered.
     *
     * @param frame the client frame to send
     * @throws IllegalStateException if the IO thread has failed
     */
    public void send(@NotNull IpcFrame.ClientFrame frame) {
        if (closed) throw new IllegalStateException("Client is closed");
        if (frame instanceof IpcFrame.SeqRequest sr) {
            seqHandlers.put(sr.seq(), new LinkedBlockingQueue<>(1));
        }
        outbox.offer(frame);
    }

    // ── receive ─────────────────────────────────────────────────────────────

    /**
     * Block until a frame arrives on the general incoming queue, or 120 seconds elapse.
     *
     * @return the next server frame, or {@code null} on timeout / interrupt
     */
    @Nullable
    public IpcFrame.ServerFrame receive() {
        return receive(0);
    }

    /**
     * Block until a frame arrives on the queue registered for {@code seq}, or 120 seconds elapse.
     *
     * @param seq the sequence number from a prior sequenced request call
     * @return the next server frame, or {@code null} on timeout / interrupt
     */
    @Nullable
    public IpcFrame.ServerFrame receive(long seq) {
        return receive(seq, 120, TimeUnit.SECONDS);
    }

    /**
     * Block until a frame arrives on the general incoming queue, or the timeout expires.
     *
     * @param timeout max time to wait
     * @param unit time unit
     * @return the next server frame, or {@code null} on timeout / interrupt
     */
    @Nullable
    public IpcFrame.ServerFrame receive(long timeout, @NotNull TimeUnit unit) {
        return receive(0, timeout, unit);
    }

    /**
     * Block until a frame arrives for the expected sequence number, or the timeout expires.
     *
     * @param seq the sequence number from a prior sequenced request call
     * @param timeout max time to wait
     * @param unit time unit
     * @return the next server frame, or {@code null} on timeout / interrupt
     */
    @Nullable
    public IpcFrame.ServerFrame receive(long seq, long timeout, @NotNull TimeUnit unit) {
        if (seq != 0) {
            BlockingQueue<IpcFrame.SeqResponse> queue = seqHandlers.get(seq);
            if (queue == null) {
                return null;
            }
            try {
                return queue.poll(timeout, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                seqHandlers.remove(seq);
            }
        } else {
            try {
                return incomingQueue.poll(timeout, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    // ── complete ────────────────────────────────────────────────────────────

    /**
     * Send a Complete frame and synchronously wait for the response.
     *
     * @param line current command line buffer content
     * @param timeout max time to wait
     * @param unit time unit
     * @return the complete response, or {@code null} on timeout / error
     */
    @Nullable
    public IpcFrame.CompleteResult complete(
            @NotNull String line, long timeout, @NotNull TimeUnit unit) {
        if (closed) return null;
        long seq = nextSeq.getAndIncrement();
        send(new IpcFrame.Complete(line, seq));
        IpcFrame.ServerFrame reply = receive(seq, timeout, unit);
        if (reply instanceof IpcFrame.CompleteResult cr) {
            return cr;
        }
        return null;
    }

    // ── hint ────────────────────────────────────────────────────────────────

    /**
     * Send a Hint frame and synchronously wait for the response.
     *
     * <p>Used by the tail-tip widget, which runs on the reader thread — blocking here blocks
     * keystroke processing, so the timeout should be short.
     *
     * @param line current command line buffer content
     * @param timeout max time to wait
     * @param unit time unit
     * @return the hint response, or {@code null} on timeout / error
     */
    @Nullable
    public IpcFrame.HintResult hint(@NotNull String line, long timeout, @NotNull TimeUnit unit) {
        if (closed) return null;
        long seq = nextSeq.getAndIncrement();
        send(new IpcFrame.Hint(line, seq));
        IpcFrame.ServerFrame reply = receive(seq, timeout, unit);
        if (reply instanceof IpcFrame.HintResult hr) {
            return hr;
        }
        return null;
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    /** Shut down the IO thread and release resources. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;

        // Gracefully wait for the background IO thread to exit.
        // The IO thread's finally block will close the transport socket inside its own thread
        // context.
        if (Thread.currentThread() != ioThread) {
            try {
                ioThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // The socket has been closed in the IO thread. Now close the context.
        ctx.close();
    }

    @NotNull
    public String identity() {
        return identity;
    }
}
