package top.focess.veto.terminal;

import java.util.UUID;
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

    public ZmqTerminal(@NotNull String address) {
        this.identity = UUID.randomUUID().toString();
        this.ctx = new ZContext();
        this.transport = ZmqTransport.connectDealer(ctx, address, identity);
    }

    // ── I/O ───────────────────────────────────────────────────────────────

    /** Send a frame to the backend. Non-blocking. */
    public void send(@NotNull IpcFrame frame) {
        try {
            transport.send(frame);
        } catch (Exception e) {
            throw new RuntimeException("ZMQ send failed", e);
        }
    }

    /** Block until a frame arrives from the backend, or null on interrupt. */
    @Nullable
    public IpcFrame receive() {
        String[] parts = transport.receive();
        if (parts == null || parts.length < 2) return null;
        return ZmqTransport.deserialize(parts[1]);
    }

    /** Try to receive without blocking. Returns null if nothing available. */
    @Nullable
    public IpcFrame tryReceive() {
        String[] parts = transport.tryReceive();
        if (parts == null || parts.length < 2) return null;
        return ZmqTransport.deserialize(parts[1]);
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
        transport.close();
    }

    @NotNull
    public String identity() {
        return identity;
    }
}
