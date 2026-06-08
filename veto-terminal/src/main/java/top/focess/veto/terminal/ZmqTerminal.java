package top.focess.veto.terminal;

import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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

    private final BlockingQueue<IpcFrame> replQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<IpcFrame.Done> hintQueue = new LinkedBlockingQueue<>();
    private final Thread readerThread;

    private volatile boolean closed;

    public ZmqTerminal(@NotNull String address) {
        this.identity = UUID.randomUUID().toString();
        this.ctx = new ZContext();
        this.transport = ZmqTransport.connectDealer(ctx, address, identity);
        this.readerThread = new Thread(this::readerLoop, "zmq-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    private void readerLoop() {
        while (!closed && !Thread.currentThread().isInterrupted()) {
            IpcFrame f = internalReceive();
            if (f != null) {
                if (f instanceof IpcFrame.Done done && Boolean.TRUE.equals(done.meta().get("isHint"))) {
                    hintQueue.offer(done);
                } else {
                    replQueue.offer(f);
                }
            } else {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Nullable
    private IpcFrame internalReceive() {
        socketLock.lock();
        try {
            String[] parts = transport.tryReceive();
            if (parts == null || parts.length < 2) return null;
            return ZmqTransport.deserialize(parts[1]);
        } finally {
            socketLock.unlock();
        }
    }

    // ── I/O ───────────────────────────────────────────────────────────────

    /** Send a frame to the backend. Thread-safe. */
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

    /** Block until a REPL frame arrives. */
    @Nullable
    public IpcFrame receive() {
        try {
            return replQueue.poll(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Try to receive a hint response without blocking. */
    @Nullable
    public IpcFrame.Done tryReceiveHint() {
        return hintQueue.poll();
    }

    /** Send a hint request and wait for the specific response. */
    @Nullable
    public HintInfo hint(@NotNull String input) {
        try {
            hintQueue.clear(); // Clear any stale hint responses
            send(new IpcFrame.Hint(input));
            IpcFrame.Done reply = hintQueue.poll(2, TimeUnit.SECONDS);
            if (reply != null) {
                String text = reply.content();
                if (text == null || text.isBlank()) return null;
                String desc = (String) reply.meta().get("description");
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
