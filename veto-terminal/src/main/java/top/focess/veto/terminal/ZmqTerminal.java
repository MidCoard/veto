package top.focess.veto.terminal;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.zeromq.ZContext;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.IpcMeta;
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
 *   <li><b>Read thread</b> — blocks on the socket with a 500 ms receive timeout, routing frames to
 *       {@link #replQueue} or {@link #hintQueue}. The timeout is set under {@code synchronized
 *       (this)} but {@code receive()} itself runs outside the lock so that {@link #close()} and
 *       {@link #send} are never blocked by a parked reader. Exits when {@link #closed} is set.
 *   <li><b>Write calls</b> — {@link #send(IpcFrame)} is called from the REPL or heartbeat thread.
 *       Non-blocking (ZMQ buffers).
 *   <li><b>Heartbeat thread</b> — periodically sends keep-alive frames.
 * </ul>
 *
 * <h3>Thread safety</h3>
 *
 * {@link #send} and {@link #close} are {@code synchronized} on {@code this} so they serialize with
 * each other. The reader loop sets the receive timeout under the same lock but calls {@code
 * receive()} outside it — ZMQ internally serializes socket operations so the reader and writer
 * threads do not corrupt each other.
 */
public final class ZmqTerminal implements AutoCloseable {

    private final ZContext ctx;
    private final ZmqTransport transport;
    private final String identity;

    private final BlockingQueue<IpcFrame> replQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<IpcFrame.Done> hintQueue = new LinkedBlockingQueue<>();

    private long nextHintSeq = 1;
    private volatile boolean closed;

    public ZmqTerminal(@NotNull String address) {
        this.identity = UUID.randomUUID().toString();
        this.ctx = new ZContext();
        this.transport = ZmqTransport.connectDealer(ctx, address, identity);
        System.err.println("Connecting to backend at " + address + " ...");
        handshake();
        System.err.println("Connected.");
        Thread readerThread = new Thread(this::readerLoop, "zmq-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * Send a protocol {@link IpcFrame.Hello} and block until the backend responds with a {@link
     * IpcFrame.Welcome}. Throws if the handshake times out or the backend rejects the version.
     *
     * <p>Called from the constructor before the reader thread starts, so we can safely use the
     * blocking {@code receive()} with a socket timeout instead of busy-polling.
     */
    private synchronized void handshake() {
        try {
            send(new IpcFrame.Hello(IpcFrame.PROTOCOL_VERSION));
        } catch (Exception e) {
            throw new RuntimeException("Failed to send handshake Hello", e);
        }

        // Temporary timeout — the backend may not be running, so we
        // can't block indefinitely in the constructor.
        transport.socket.setReceiveTimeOut(10_000);

        try {
            while (true) {
                ZmqTransport.ZmqMessage msg = transport.receive();
                if (msg == null) {
                    throw new RuntimeException("Handshake timed out — backend may be incompatible");
                }
                IpcFrame frame = msg.frame();
                if (frame instanceof IpcFrame.Welcome w) {
                    return;
                }
                if (frame instanceof IpcFrame.Error e) {
                    throw new RuntimeException("Backend rejected handshake: " + e.content());
                }
                // Unknown frame — keep waiting
            }
        } finally {
            // Reset to infinite — the reader loop will be unblocked by
            // close() shutting down the socket, not by a timeout.
            transport.socket.setReceiveTimeOut(-1);
        }
    }

    private void readerLoop() {
        // Set a receive timeout under the lock so the reader thread wakes
        // periodically to check the closed flag. The actual receive() call
        // is outside the lock — otherwise close() could never acquire it
        // and the thread would be unkillable.
        synchronized (this) {
            transport.socket.setReceiveTimeOut(500);
        }

        while (!closed) {
            ZmqTransport.ZmqMessage msg = transport.receive();
            if (msg == null) {
                // Timeout or socket closed — loop back to check closed
                continue;
            }
            IpcFrame f = msg.frame();
            if (f == null) continue;
            if (f instanceof IpcFrame.Done done
                    && Boolean.TRUE.equals(done.meta().get(IpcMeta.IS_HINT))) {
                hintQueue.offer(done);
            } else {
                replQueue.offer(f);
            }
        }
    }

    // ── I/O ───────────────────────────────────────────────────────────────

    /** Send a frame to the backend. Thread-safe. */
    public synchronized void send(@NotNull IpcFrame frame) {
        if (closed) return;
        try {
            transport.send(frame);
        } catch (Exception e) {
            throw new RuntimeException("ZMQ send failed", e);
        }
    }

    /** Block until a REPL frame arrives (120s timeout). */
    @Nullable
    public IpcFrame receive() {
        return receive(120, TimeUnit.SECONDS);
    }

    /** Block until a REPL frame arrives or the timeout expires. */
    @Nullable
    public IpcFrame receive(long timeout, @NotNull TimeUnit unit) {
        try {
            return replQueue.poll(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Send a Hint frame and synchronously wait for the response. Used by the tail-tip widget, which
     * runs on the reader thread — blocking here blocks keystroke processing, so the timeout is
     * short.
     *
     * @param line current command line buffer content
     * @param timeout max time to wait
     * @param unit time unit
     * @return the hint response, or {@code null} on timeout / error
     */
    @Nullable
    public IpcFrame.Done hint(@NotNull String line, long timeout, @NotNull TimeUnit unit) {
        synchronized (this) {
            send(new IpcFrame.Hint(line, nextHintSeq++));
        }
        try {
            return hintQueue.poll(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ── lifecycle ────────────────────────────────────────────────────────

    @Override
    public synchronized void close() {
        closed = true;
        // Close the socket first — this unblocks the reader thread which is
        // parked in transport.receive(). ZContext.close() comes after so the
        // context stays alive until the socket is done.
        transport.close();
        ctx.close();
    }

    @NotNull
    public String identity() {
        return identity;
    }
}
