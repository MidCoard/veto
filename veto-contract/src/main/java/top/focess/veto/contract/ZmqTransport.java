package top.focess.veto.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;

/**
 * ZeroMQ transport for Veto terminal ↔ backend communication.
 *
 * <h2>Socket pattern</h2>
 *
 * The backend binds a {@link SocketType#ROUTER} socket. Each terminal connects with a {@link
 * SocketType#DEALER} socket carrying a unique identity. ZMQ handles routing, framing, and
 * connection lifecycle — no hand-rolled file locking or state polling.
 *
 * <h2>Wire format</h2>
 *
 * Each ZMQ message is a single JSON-serialized {@link IpcFrame}. ROUTER sockets receive an identity
 * frame followed by the payload frame; DEALER sockets send and receive bare payloads.
 *
 * <h2>Thread safety</h2>
 *
 * <b>This class is not thread-safe.</b> JeroMQ sockets are not safe for concurrent use. Callers
 * must serialize all socket access externally — see {@code ZmqClient#ioLoop} and {@code
 * ZmqServer#ioLoop} (single-threaded event loop owning the socket) for the supported pattern.
 */
public final class ZmqTransport implements AutoCloseable {

    private static final Logger log = Logger.getLogger(ZmqTransport.class.getName());

    static final ObjectMapper JSON = new ObjectMapper();

    public final ZMQ.Socket socket;
    private final SocketType type;

    ZmqTransport(ZMQ.Socket socket, SocketType type) {
        this.socket = socket;
        this.type = type;
    }

    // ── types ────────────────────────────────────────────────────────────

    /**
     * A received ZeroMQ message. On ROUTER sockets {@link #identity} carries the sender's routing
     * identity; on DEALER sockets it is empty.
     */
    public record ZmqMessage(String identity, IpcFrame frame) {}

    // ── factory ──────────────────────────────────────────────────────────

    /** Backend: create a ROUTER bound to the given endpoint. */
    public static ZmqTransport bindRouter(ZContext ctx, String addr) {
        ZMQ.Socket sock = ctx.createSocket(SocketType.ROUTER);
        sock.bind(addr);
        return new ZmqTransport(sock, SocketType.ROUTER);
    }

    /** Terminal: create a DEALER connected to the backend. */
    public static ZmqTransport connectDealer(ZContext ctx, String addr, String identity) {
        ZMQ.Socket sock = ctx.createSocket(SocketType.DEALER);
        sock.setIdentity(identity.getBytes(ZMQ.CHARSET));
        sock.connect(addr);
        return new ZmqTransport(sock, SocketType.DEALER);
    }

    // ── send ─────────────────────────────────────────────────────────────

    /** Send a frame to a specific peer identity (ROUTER only). */
    public void send(String identity, IpcFrame frame) {
        log.fine("Router sending to [" + identity + "]: " + frame);
        byte[] payload = serialize(frame);
        if (payload == null) return;
        socket.sendMore(identity.getBytes(ZMQ.CHARSET));
        socket.send(payload);
    }

    /** Send a frame (DEALER). */
    public void send(IpcFrame frame) {
        log.fine("Dealer sending: " + frame);
        byte[] payload = serialize(frame);
        if (payload == null) return;
        socket.send(payload);
    }

    // ── receive ──────────────────────────────────────────────────────────

    /**
     * Try to receive a message without blocking. Returns {@code null} if nothing is available.
     *
     * <p>On ROUTER sockets the returned {@link ZmqMessage#identity} carries the sender's routing
     * identity. On DEALER sockets {@code identity} is empty.
     */
    public ZmqMessage tryReceive() {
        return decodeMsg(ZMsg.recvMsg(socket, ZMQ.DONTWAIT));
    }

    /** Block until a message arrives, or {@code null} on interrupt. */
    public ZmqMessage receive() {
        return decodeMsg(ZMsg.recvMsg(socket));
    }

    /**
     * Decode a raw ZMsg into a {@link ZmqMessage}. On ROUTER sockets the first frame is the sender
     * identity; on DEALER sockets there is a single payload frame. The payload is deserialized to
     * an {@link IpcFrame}.
     */
    private ZmqMessage decodeMsg(ZMsg msg) {
        if (msg == null || msg.isEmpty()) return null;

        final String identity;
        final String payload;

        if (type == SocketType.ROUTER) {
            if (msg.size() < 2) {
                msg.destroy();
                return null;
            }
            identity = new String(msg.pop().getData(), StandardCharsets.UTF_8);
            payload = new String(msg.pop().getData(), StandardCharsets.UTF_8);
        } else {
            identity = "";
            payload = new String(msg.pop().getData(), StandardCharsets.UTF_8);
        }
        msg.destroy();

        IpcFrame frame = deserialize(payload);
        if (frame == null) {
            log.warning("Failed to deserialize payload: " + payload);
            return null;
        }
        if (type == SocketType.ROUTER) {
            log.fine("Router received from [" + identity + "]: " + frame);
        } else {
            log.fine("Dealer received: " + frame);
        }
        return new ZmqMessage(identity, frame);
    }

    /** Serialize a frame to JSON bytes, or {@code null} on failure. */
    public static byte[] serialize(IpcFrame frame) {
        try {
            return JSON.writeValueAsBytes(frame);
        } catch (JsonProcessingException e) {
            log.log(Level.WARNING, "Failed to serialize frame: " + frame, e);
            return null;
        }
    }

    /** Deserialize a payload string back to an IpcFrame, or {@code null} on failure. */
    public static IpcFrame deserialize(String payload) {
        try {
            return JSON.readValue(payload, IpcFrame.class);
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to deserialize payload: " + payload, e);
            return null;
        }
    }

    // ── lifecycle ────────────────────────────────────────────────────────

    /** Close the transport socket. */
    @Override
    public void close() {
        socket.close();
    }

    public boolean isRouter() {
        return type == SocketType.ROUTER;
    }
}
