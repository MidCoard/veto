package top.focess.veto.terminal;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.zeromq.ZContext;
import top.focess.veto.contract.HintInfo;
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
 *   <li><b>Read thread</b> — blocks on {@link #receive()} for incoming frames from the backend. The
 *       REPL thread feeds these to the renderer.
 *   <li><b>Write calls</b> — {@link #send(IpcFrame)} is called from the REPL or heartbeat thread.
 *       Non-blocking (ZMQ buffers).
 *   <li><b>Heartbeat thread</b> — periodically sends keep-alive frames.
 * </ul>
 */
public final class ZmqTerminal implements AutoCloseable {

    private final ZContext ctx;
    private final ZmqTransport transport;
    private final String identity;

    /** Serializes all socket access — JeroMQ sockets are not thread-safe. */
    private final ReentrantLock socketLock = new ReentrantLock();

    private volatile boolean closed;

    public ZmqTerminal(@NotNull String address) {
        this.identity = UUID.randomUUID().toString();
        this.ctx = new ZContext();
        this.transport = ZmqTransport.connectDealer(ctx, address, identity);
    }

    // ── I/O ───────────────────────────────────────────────────────────────

    /** Send a frame to the backend. Thread-safe; serialized with reads via {@link #socketLock}. */
    public void send(@NotNull IpcFrame frame) {
        socketLock.lock();
        try {
            transport.send(frame);
        } catch (Exception e) {
            throw new RuntimeException("ZMQ send failed", e);
        } finally {
            socketLock.unlock();
        }
    }

    /**
     * Block until a frame arrives, or return {@code null} on close/interrupt.
     *
     * <p>Implemented as a short poll loop rather than a blocking socket read so the single ZMQ
     * socket is never held by one thread while another (heartbeat, hint reader) needs it. JeroMQ
     * sockets are not thread-safe; {@link #socketLock} guarantees one-at-a-time access.
     */
    @Nullable
    public IpcFrame receive() {
        while (!closed && !Thread.currentThread().isInterrupted()) {
            IpcFrame f = tryReceive();
            if (f != null) return f;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    /** Try to receive without blocking. Returns null if nothing available. Thread-safe. */
    @Nullable
    public IpcFrame tryReceive() {
        socketLock.lock();
        try {
            String[] parts = transport.tryReceive();
            if (parts == null || parts.length < 2) return null;
            return ZmqTransport.deserialize(parts[1]);
        } finally {
            socketLock.unlock();
        }
    }

    /** Send a hint request. Returns null if the backend didn't respond. */
    @Nullable
    public HintInfo hint(@NotNull String input) {
        try {
            send(new IpcFrame.Hint(input));
            IpcFrame reply = receive();
            if (reply instanceof IpcFrame.Done done) {
                String text = done.content();
                if (text == null || text.isBlank()) return null;
                String desc = (String) done.meta().get("description");
                return new HintInfo(text, desc);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ── lifecycle ────────────────────────────────────────────────────────

    @Override
    public void close() {
        closed = true;
        socketLock.lock();
        try {
            transport.close();
        } finally {
            socketLock.unlock();
        }
    }

    @NotNull
    public String identity() {
        return identity;
    }
}
