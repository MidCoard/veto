package top.focess.veto.transport.zmq;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;
import top.focess.veto.contract.ClientTransport;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.contract.ProtocolClient;
import top.focess.veto.contract.ServerTransport;

/**
 * ZeroMQ framing and resources.
 *
 * <p><b>Thread safety:</b> channel instances are not thread-safe. One exclusive owner must perform
 * every send, recv and close; neither the socket, poller nor closed flag is protected for
 * concurrent access. In particular, another thread must not close a channel to interrupt recv.
 *
 * <p>Ownership may move between threads using safe publication and no overlapping operations, such
 * as initialization before thread start and cleanup after a completed thread join. Independent
 * channel instances may run on different owner threads. A shared context does not make its channels
 * thread-safe. Repeated close is supported only under this ownership contract.
 */
public abstract class ZmqChannel {
    protected final ZMQ.@NonNull Socket socket;
    private final ZMQ.@NonNull Poller poller;
    private boolean closed;

    private ZmqChannel(ZMQ.@NonNull Socket socket, @NonNull ZContext context) {
        this.socket = socket;
        var created = context.createPoller(1);
        try {
            if (created.register(socket, ZMQ.Poller.POLLIN) < 0)
                throw new IllegalStateException("Cannot register ZeroMQ socket");
        } catch (Throwable failure) {
            try {
                created.close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        poller = created;
    }

    protected ZMsg receive(long timeoutMillis, int parts) {
        if (closed) throw new IllegalStateException("ZeroMQ channel is closed");
        if (poller.poll(timeoutMillis < 0 ? -1 : timeoutMillis) <= 0 || !poller.pollin(0))
            return null;
        var message = ZMsg.recvMsg(socket, ZMQ.DONTWAIT);
        if (message != null && message.size() != parts) {
            message.destroy();
            return null;
        }
        return message;
    }

    public void close() {
        if (closed) return;
        closed = true;
        try {
            poller.close();
        } finally {
            socket.close();
        }
    }

    private static ZMQ.@NonNull Socket open(
            @NonNull ZContext context,
            @NonNull SocketType type,
            @NonNull String address,
            String identity) {
        var socket = context.createSocket(type);
        try {
            // A slow ROUTER peer must fail admission, not drop frames or block every other peer.
            if (!socket.setSendTimeOut(type == SocketType.ROUTER ? 0 : 1_000)
                    || !socket.setLinger(1_000))
                throw new IllegalStateException("Cannot configure ZeroMQ timeouts");
            if (type == SocketType.ROUTER && !socket.setRouterMandatory(true))
                throw new IllegalStateException("Cannot configure ZeroMQ routing failures");
            if (identity != null && !socket.setIdentity(identity.getBytes(ZMQ.CHARSET)))
                throw new IllegalStateException("Cannot configure ZeroMQ identity");
            if (!(type == SocketType.ROUTER ? socket.bind(address) : socket.connect(address)))
                throw new IllegalStateException("Cannot open ZeroMQ channel");
            return socket;
        } catch (Throwable failure) {
            try {
                socket.close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /**
     * Not thread-safe; follows {@link ZmqChannel}'s exclusive-owner contract. Always owns and
     * closes its context. Use {@link ProtocolClient} for concurrent application callers.
     */
    public static final class Client extends ZmqChannel implements ClientTransport {
        private final @NonNull ZContext context;

        private Client(ZMQ.@NonNull Socket socket, @NonNull ZContext context) {
            super(socket, context);
            this.context = context;
        }

        /**
         * Opens a client with its own context, releasing partial resources if setup fails.
         *
         * @throws RuntimeException if the address is invalid or socket/poller setup fails
         */
        public static @NonNull Client connect(@NonNull String address) {
            var context = new ZContext();
            try {
                var socket =
                        open(context, SocketType.DEALER, address, UUID.randomUUID().toString());
                return new Client(socket, context);
            } catch (Throwable failure) {
                try {
                    context.close();
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        @Override
        public void close() {
            try {
                super.close();
            } finally {
                context.close();
            }
        }

        @Override
        public void send(Frame.@NonNull ClientFrame frame) {
            if (!socket.send(FrameCodec.encode(frame)))
                throw new IllegalStateException("ZeroMQ send failed");
        }

        @Override
        public Frame.ServerFrame recv(long timeoutMillis) {
            var message = receive(timeoutMillis, 1);
            if (message == null) return null;
            try {
                var frame = FrameCodec.decode(message.getFirst().getData());
                return frame instanceof Frame.ServerFrame server ? server : null;
            } finally {
                message.destroy();
            }
        }
    }

    /**
     * Not thread-safe; follows {@link ZmqChannel}'s exclusive-owner contract. Borrows its context;
     * the context owner must close this channel before closing the context.
     */
    public static final class Server extends ZmqChannel implements ServerTransport {
        private Server(ZMQ.@NonNull Socket socket, @NonNull ZContext context) {
            super(socket, context);
        }

        public static @NonNull Server bindRouter(
                @NonNull ZContext context, @NonNull String address) {
            var socket = open(context, SocketType.ROUTER, address, null);
            try {
                return new Server(socket, context);
            } catch (Throwable failure) {
                try {
                    socket.close();
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        @Override
        public void send(@NonNull String identity, Frame.@NonNull ServerFrame frame) {
            byte[] payload = FrameCodec.encode(frame);
            if (!socket.sendMore(identity.getBytes(ZMQ.CHARSET)) || !socket.send(payload))
                throw new IllegalStateException("ZeroMQ send failed");
        }

        @Override
        public Message recv(long timeoutMillis) {
            var message = receive(timeoutMillis, 2);
            if (message == null) return null;
            try {
                String identity = new String(message.getFirst().getData(), ZMQ.CHARSET);
                var frame = FrameCodec.decode(message.getLast().getData());
                return frame instanceof Frame.ClientFrame client
                        ? new Message(identity, client)
                        : null;
            } finally {
                message.destroy();
            }
        }
    }
}
