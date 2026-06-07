package top.focess.veto.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
     */
    public String[] tryReceive() {
        ZMsg msg = ZMsg.recvMsg(socket, ZMQ.DONTWAIT);
        if (msg == null || msg.isEmpty()) return null;

        if (type == SocketType.ROUTER) {
            if (msg.size() < 2) {
                msg.destroy();
                return null;
            }
            String identity = msg.popString();
            String payload = msg.popString();
            msg.destroy();
            return new String[] {identity, payload};
        } else {
            // DEALER: join all frames
            StringBuilder sb = new StringBuilder();
            while (!msg.isEmpty()) {
                sb.append(msg.popString());
            }
            msg.destroy();
            return new String[] {"", sb.toString()};
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
            String identity = msg.popString();
            String payload = msg.popString();
            msg.destroy();
            return new String[] {identity, payload};
        } else {
            String payload = msg.popString();
            msg.destroy();
            return new String[] {"", payload};
        }
    }

    /** Serialize a frame to JSON bytes. */
    public static byte[] serialize(IpcFrame frame) throws JsonProcessingException {
        return JSON.writeValueAsBytes(frame);
    }

    /** Deserialize a payload string back to an IpcFrame. */
    public static IpcFrame deserialize(String payload) {
        if (payload == null) return null;
        try {
            return JSON.readValue(payload, IpcFrame.class);
        } catch (Exception e) {
            return new IpcFrame.Error("Deserialization failed: " + e.getMessage());
        }
    }

    // ── lifecycle ────────────────────────────────────────────────────────

    @Override
    public void close() {
        socket.close();
        ctx.close();
    }

    public ZMQ.Socket socket() {
        return socket;
    }

    public boolean isRouter() {
        return type == SocketType.ROUTER;
    }
}
