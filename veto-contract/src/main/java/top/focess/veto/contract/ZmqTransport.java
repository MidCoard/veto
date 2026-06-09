package top.focess.veto.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;

/**
 * ZeroMQ transport for Veto terminal ↔ backend communication.
 *
 * <h3>Socket pattern</h3>
 *
 * The backend binds a {@link SocketType#ROUTER} socket. Each terminal connects with a {@link
 * SocketType#DEALER} socket carrying a unique identity. ZMQ handles routing, framing, and
 * connection lifecycle — no hand-rolled file locking or state polling.
 *
 * <h3>Wire format</h3>
 *
 * Each ZMQ message is a single JSON-serialized {@link IpcFrame}. ROUTER sockets receive an identity
 * frame followed by the payload frame; DEALER sockets send and receive bare payloads.
 */
public final class ZmqTransport implements AutoCloseable {

    static final ObjectMapper JSON = new ObjectMapper();

    private final ZContext ctx;
    private final ZMQ.Socket socket;
    private final SocketType type;

    ZmqTransport(ZContext ctx, ZMQ.Socket socket, SocketType type) {
        this.ctx = ctx;
        this.socket = socket;
        this.type = type;
    }

    // ── factory ──────────────────────────────────────────────────────────

    /** Backend: create a ROUTER bound to the given endpoint. */
    public static ZmqTransport bindRouter(ZContext ctx, String addr) {
        ZMQ.Socket sock = ctx.createSocket(SocketType.ROUTER);
        sock.bind(addr);
        return new ZmqTransport(ctx, sock, SocketType.ROUTER);
    }

    /** Terminal: create a DEALER connected to the backend. */
    public static ZmqTransport connectDealer(ZContext ctx, String addr, String identity) {
        ZMQ.Socket sock = ctx.createSocket(SocketType.DEALER);
        sock.setIdentity(identity.getBytes(ZMQ.CHARSET));
        sock.connect(addr);
        return new ZmqTransport(ctx, sock, SocketType.DEALER);
    }

    // ── send ─────────────────────────────────────────────────────────────

    /** Send a frame. On ROUTER, identity must be set. On DEALER, it's ignored. */
    public void send(String identity, IpcFrame frame) throws JsonProcessingException {
        byte[] payload = JSON.writeValueAsBytes(frame);
        if (type == SocketType.ROUTER) {
            socket.sendMore(identity.getBytes(ZMQ.CHARSET));
            socket.send(payload);
        } else {
            socket.send(payload);
        }
    }

    /** DEALER convenience: send without identity. */
    public void send(IpcFrame frame) throws JsonProcessingException {
        send("", frame);
    }

    // ── receive ──────────────────────────────────────────────────────────

    /**
     * Try to receive a frame. Returns {@code null} if nothing is available. On ROUTER, returns the
     * identity in the first element and the payload in the second.
     *
     * <p>Explicitly decodes ZMQ frame bytes as UTF-8 rather than relying on {@link
     * ZMsg#popString()}, which hex-encodes the data on some platforms (JeroMQ 0.6.0 / GBK locale).
     */
    public String[] tryReceive() {
        ZMsg msg = ZMsg.recvMsg(socket, ZMQ.DONTWAIT);
        if (msg == null || msg.isEmpty()) return null;

        if (type == SocketType.ROUTER) {
            if (msg.size() < 2) {
                msg.destroy();
                return null;
            }
            String identity = new String(msg.pop().getData(), StandardCharsets.UTF_8);
            String payload = new String(msg.pop().getData(), StandardCharsets.UTF_8);
            msg.destroy();
            return new String[] {identity, payload};
        } else {
            // DEALER: single frame — DEALER sockets receive one frame per message
            String payload = new String(msg.pop().getData(), StandardCharsets.UTF_8);
            msg.destroy();
            return new String[] {"", payload};
        }
    }

    /** Block until a frame arrives, or null on interrupt. */
    public String[] receive() {
        ZMsg msg = ZMsg.recvMsg(socket);
        if (msg == null || msg.isEmpty()) return null;

        if (type == SocketType.ROUTER) {
            if (msg.size() < 2) {
                msg.destroy();
                return null;
            }
            String identity = new String(msg.pop().getData(), StandardCharsets.UTF_8);
            String payload = new String(msg.pop().getData(), StandardCharsets.UTF_8);
            msg.destroy();
            return new String[] {identity, payload};
        } else {
            String payload = new String(msg.pop().getData(), StandardCharsets.UTF_8);
            msg.destroy();
            return new String[] {"", payload};
        }
    }

    /** Serialize a frame to JSON bytes. */
    public static byte[] serialize(IpcFrame frame) throws JsonProcessingException {
        return JSON.writeValueAsBytes(frame);
    }

    /**
     * Deserialize a payload string back to an IpcFrame.
     *
     * <p>Returns {@code null} on deserialization failure so callers can distinguish transport-level
     * corruption (skip / reconnect) from application-level {@link IpcFrame.Error} frames (display
     * to user). All existing callers already handle {@code null} as "no usable frame received."
     */
    public static IpcFrame deserialize(String payload) {
        if (payload == null) return null;
        try {
            return JSON.readValue(payload, IpcFrame.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ── lifecycle ────────────────────────────────────────────────────────

    /**
     * Close the transport socket. The {@link ZContext} lifecycle is managed by the owning component
     * — callers must close the context themselves after all transports are closed.
     */
    @Override
    public void close() {
        socket.close();
    }

    public ZMQ.Socket socket() {
        return socket;
    }

    public boolean isRouter() {
        return type == SocketType.ROUTER;
    }
}
