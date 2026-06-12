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

    /** The underlying ZeroMQ socket instance. */
    public final ZMQ.Socket socket;

    /** The SocketType (e.g. ROUTER or DEALER) of the transport socket. */
    private final SocketType type;

    /**
     * package-private constructor used by binding and connecting factory methods.
     *
     * @param socket the initialized ZMQ socket
     * @param type the socket type (ROUTER / DEALER)
     */
    ZmqTransport(ZMQ.Socket socket, SocketType type) {
        this.socket = socket;
        this.type = type;
    }

    // ── types ────────────────────────────────────────────────────────────

    /**
     * Represents a received transport message wrapper.
     *
     * @param identity the sender's ZMQ routing identity (populated for ROUTER socket types, empty
     *     for DEALERs)
     * @param frame the deserialized IPC frame payload
     */
    public record ZmqMessage(String identity, IpcFrame frame) {}

    // ── factory ──────────────────────────────────────────────────────────

    /**
     * Backend factory method: binds a ZMQ ROUTER socket to the specified TCP/IPC address endpoint.
     *
     * @param ctx the shared ZeroMQ context
     * @param addr the socket bind address (e.g., {@code tcp://*:5555})
     * @return the configured ZmqTransport instance
     */
    public static ZmqTransport bindRouter(ZContext ctx, String addr) {
        ZMQ.Socket sock = ctx.createSocket(SocketType.ROUTER);
        sock.bind(addr);
        return new ZmqTransport(sock, SocketType.ROUTER);
    }

    /**
     * Client factory method: connects a ZMQ DEALER socket with the specified identity to the
     * backend ROUTER.
     *
     * @param ctx the shared ZeroMQ context
     * @param addr the backend connection address (e.g., {@code tcp://127.0.0.1:5555})
     * @param identity the unique client identity used for ZMQ message routing
     * @return the configured ZmqTransport instance
     */
    public static ZmqTransport connectDealer(ZContext ctx, String addr, String identity) {
        ZMQ.Socket sock = ctx.createSocket(SocketType.DEALER);
        sock.setIdentity(identity.getBytes(ZMQ.CHARSET));
        sock.connect(addr);
        return new ZmqTransport(sock, SocketType.DEALER);
    }

    // ── send ─────────────────────────────────────────────────────────────

    /**
     * Sends a frame to a specific peer identity. Only valid for ROUTER sockets.
     *
     * @param identity the destination peer identity
     * @param frame the frame payload to send
     */
    public void send(String identity, IpcFrame frame) {
        log.fine("Router sending to [" + identity + "]: " + frame);
        byte[] payload = serialize(frame);
        if (payload == null) return;
        // ROUTER sockets expect two frames: the destination routing identity frame followed by the
        // payload.
        socket.sendMore(identity.getBytes(ZMQ.CHARSET));
        socket.send(payload);
    }

    /**
     * Sends a frame directly to the backend. Only valid for DEALER sockets.
     *
     * @param frame the frame payload to send
     */
    public void send(IpcFrame frame) {
        log.fine("Dealer sending: " + frame);
        byte[] payload = serialize(frame);
        if (payload == null) return;
        // DEALER sockets automatically prepend the identity frame and send the bare payload frame.
        socket.send(payload);
    }

    // ── receive ──────────────────────────────────────────────────────────

    /**
     * Attempts to read a message from the socket without blocking.
     *
     * @return the received message, or null if no messages are currently available
     */
    public ZmqMessage tryReceive() {
        return decodeMsg(ZMsg.recvMsg(socket, ZMQ.DONTWAIT));
    }

    /**
     * Blocks until a message is received from the socket.
     *
     * @return the received message, or null if interrupted
     */
    public ZmqMessage receive() {
        return decodeMsg(ZMsg.recvMsg(socket));
    }

    /**
     * Decodes a raw multipart ZMsg. For ROUTER sockets, populates the identity frame. For DEALER
     * sockets, decodes the single frame. Deserializes the JSON frame data back into an IpcFrame
     * instance.
     *
     * @param msg the raw ZMsg to parse
     * @return the decoded ZmqMessage, or null on format error
     */
    private ZmqMessage decodeMsg(ZMsg msg) {
        if (msg == null || msg.isEmpty()) return null;

        final String identity;
        final String payload;

        if (type == SocketType.ROUTER) {
            // ROUTER envelopes carry at least: 1. Identity frame, 2. Payload frame.
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

    /**
     * Serializes an IpcFrame instance to JSON bytes.
     *
     * @param frame the frame instance to serialize
     * @return the serialized JSON byte array, or null on exception
     */
    public static byte[] serialize(IpcFrame frame) {
        try {
            return JSON.writeValueAsBytes(frame);
        } catch (JsonProcessingException e) {
            log.log(Level.WARNING, "Failed to serialize frame: " + frame, e);
            return null;
        }
    }

    /**
     * Deserializes a raw JSON string back into an IpcFrame instance.
     *
     * @param payload the JSON string data
     * @return the deserialized IpcFrame instance, or null on format error
     */
    public static IpcFrame deserialize(String payload) {
        try {
            return JSON.readValue(payload, IpcFrame.class);
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to deserialize payload: " + payload, e);
            return null;
        }
    }

    // ── lifecycle ────────────────────────────────────────────────────────

    /** Closes the underlying ZeroMQ transport socket. */
    @Override
    public void close() {
        socket.close();
    }

    /**
     * Checks whether this transport represents a ROUTER socket type.
     *
     * @return true if ROUTER, false if DEALER
     */
    public boolean isRouter() {
        return type == SocketType.ROUTER;
    }
}
