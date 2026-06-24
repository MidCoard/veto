package top.focess.veto.contract;

import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;

/**
 * ZMQ-backed {@link Transport}. A single base owns the socket, poller, and multipart-envelope
 * decode; two nested concrete channels expose the type-safe send shapes:
 *
 * <ul>
 *   <li>{@link Client} — a DEALER socket (implements {@link ClientTransport}); created via {@link
 *       Client#connectDealer}.
 *   <li>{@link Server} — a ROUTER socket (implements {@link ServerTransport}); created via {@link
 *       Server#bindRouter}.
 * </ul>
 *
 * <h2>Thread safety</h2>
 *
 * <b>Not thread-safe.</b> JeroMQ sockets are not safe for concurrent use. Callers must serialize
 * all access — the supported pattern is a single IO thread owning the channel (see {@code
 * IpcClient#ioLoop} and {@code IpcServer#ioLoop}).
 *
 * <p>Unlike the prior {@code ZmqTransport}, the raw {@link ZMQ.Socket} is encapsulated (no public
 * field) and framing is delegated to {@link IpcCodec}, so this class concerns itself only with
 * socket lifecycle and the ZMQ multipart envelope.
 */
public abstract class ZmqChannel {

    private static final Logger log = LoggerFactory.getLogger(ZmqChannel.class);

    protected final ZMQ.Socket socket;
    private final ZMQ.Poller poller;
    private final SocketType type;

    private ZmqChannel(
            @NotNull ZMQ.Socket socket, @NotNull SocketType type, @NotNull ZContext ctx) {
        this.socket = socket;
        this.type = type;
        this.poller = ctx.createPoller(1);
        this.poller.register(socket, ZMQ.Poller.POLLIN);
    }

    // ── Transport: recv / close ──────────────────────────────────────────

    /**
     * Receives the next framed message, honoring the {@link Transport} timeout convention.
     *
     * @param timeoutMillis {@code 0} non-blocking, {@code >0} up to N ms, {@code <0} infinite
     * @return the next message, or {@code null} on timeout or dropped malformed payload
     */
    @Nullable
    public Transport.FramedMsg recv(long timeoutMillis) {
        long rc = poller.poll(timeoutMillis < 0 ? -1 : timeoutMillis);
        if (rc <= 0 || !poller.pollin(0)) return null;
        ZMsg msg = ZMsg.recvMsg(socket, ZMQ.DONTWAIT);
        return decode(msg);
    }

    /**
     * Closes the poller and socket.
     *
     * <p>Single-threaded contract: the owning thread that runs {@link #recv} is the thread that
     * calls {@code close}. There is deliberately no liveness flag — guarding one field with {@code
     * volatile} cannot make concurrent socket use safe (the socket itself would race), so such a
     * guard would be thread-safety theater. Callers that need a stop signal own their own flag (see
     * {@code IpcClient#closed}, {@code IpcServer#running}); the loop exits when that flag flips and
     * {@code close} is then called from the owning thread.
     */
    public void close() {
        try {
            poller.close();
        } catch (Exception ignored) {
        }
        socket.close();
    }

    // ── envelope decode ──────────────────────────────────────────────────

    @Nullable
    private Transport.FramedMsg decode(@Nullable ZMsg msg) {
        if (msg == null || msg.isEmpty()) {
            if (msg != null) msg.destroy();
            return null;
        }
        final String identity;
        final byte[] payload;
        if (type == SocketType.ROUTER) {
            // ROUTER envelopes: [identity][payload] (at least).
            if (msg.size() < 2) {
                msg.destroy();
                return null;
            }
            identity = new String(msg.pop().getData(), StandardCharsets.UTF_8);
            payload = msg.pop().getData();
        } else {
            identity = "";
            payload = msg.pop().getData();
        }
        msg.destroy();

        IpcFrame frame = IpcCodec.decode(payload);
        if (frame == null) {
            log.warn("Dropped malformed frame from [{}]", identity);
            return null;
        }
        return new Transport.FramedMsg(identity, frame);
    }

    // ── concrete channels ────────────────────────────────────────────────

    /** A DEALER-backed {@link ClientTransport}. */
    public static final class Client extends ZmqChannel implements ClientTransport {

        private Client(@NotNull ZMQ.Socket socket, @NotNull ZContext ctx) {
            super(socket, SocketType.DEALER, ctx);
        }

        /**
         * Connects a DEALER socket with the given identity to the backend ROUTER.
         *
         * @param ctx the shared ZeroMQ context
         * @param addr the backend connect address (e.g. {@code tcp://127.0.0.1:5555})
         * @param identity the unique client identity used for ZMQ routing
         * @return a connected client channel
         */
        @NotNull
        public static Client connectDealer(
                @NotNull ZContext ctx, @NotNull String addr, @NotNull String identity) {
            ZMQ.Socket sock = ctx.createSocket(SocketType.DEALER);
            sock.setIdentity(identity.getBytes(ZMQ.CHARSET));
            sock.connect(addr);
            return new Client(sock, ctx);
        }

        @Override
        public void send(@NotNull IpcFrame.ClientFrame frame) {
            // DEALER sockets automatically prepend the identity frame and send the bare payload.
            socket.send(IpcCodec.encode(frame));
        }
    }

    /** A ROUTER-backed {@link ServerTransport}. */
    public static final class Server extends ZmqChannel implements ServerTransport {

        private Server(@NotNull ZMQ.Socket socket, @NotNull ZContext ctx) {
            super(socket, SocketType.ROUTER, ctx);
        }

        /**
         * Binds a ROUTER socket to the given address.
         *
         * @param ctx the shared ZeroMQ context
         * @param addr the bind address (e.g. {@code tcp://*:5555})
         * @return a bound server channel
         */
        @NotNull
        public static Server bindRouter(@NotNull ZContext ctx, @NotNull String addr) {
            ZMQ.Socket sock = ctx.createSocket(SocketType.ROUTER);
            sock.bind(addr);
            return new Server(sock, ctx);
        }

        @Override
        public void send(@NotNull String identity, @NotNull IpcFrame frame) {
            // ROUTER sockets expect [identity][payload].
            socket.sendMore(identity.getBytes(ZMQ.CHARSET));
            socket.send(IpcCodec.encode(frame));
        }
    }
}
