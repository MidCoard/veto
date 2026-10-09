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
import top.focess.veto.contract.ServerTransport;

/** ZeroMQ framing and resources. One IO owner serializes all socket operations. */
public abstract class ZmqChannel {
    protected final ZMQ.@NonNull Socket socket;
    private final ZMQ.@NonNull Poller poller;
    private final ZContext ownedContext;
    private boolean closed;

    private ZmqChannel(ZMQ.@NonNull Socket socket, @NonNull ZContext context, boolean owned) {
        this.socket = socket;
        ownedContext = owned ? context : null;
        var created = context.createPoller(1);
        try {
            if (created.register(socket, ZMQ.Poller.POLLIN) < 0)
                throw new IllegalStateException("Cannot register ZeroMQ socket");
        } catch (RuntimeException | Error failure) {
            try {
                created.close();
            } catch (RuntimeException | Error cleanup) {
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
            try {
                socket.close();
            } finally {
                if (ownedContext != null) ownedContext.close();
            }
        }
    }

    private static ZMQ.@NonNull Socket open(
            @NonNull ZContext context,
            @NonNull SocketType type,
            @NonNull String address,
            String identity) {
        var socket = context.createSocket(type);
        try {
            if (!socket.setSendTimeOut(1_000) || !socket.setLinger(1_000))
                throw new IllegalStateException("Cannot configure ZeroMQ timeouts");
            if (identity != null && !socket.setIdentity(identity.getBytes(ZMQ.CHARSET)))
                throw new IllegalStateException("Cannot configure ZeroMQ identity");
            if (!(type == SocketType.ROUTER ? socket.bind(address) : socket.connect(address)))
                throw new IllegalStateException("Cannot open ZeroMQ channel");
            return socket;
        } catch (RuntimeException | Error failure) {
            try {
                socket.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public static final class Client extends ZmqChannel implements ClientTransport {
        private Client(ZMQ.@NonNull Socket socket, @NonNull ZContext context, boolean owned) {
            super(socket, context, owned);
        }

        private static @NonNull Client connect(
                @NonNull ZContext context,
                @NonNull String address,
                @NonNull String identity,
                boolean owned) {
            var socket = open(context, SocketType.DEALER, address, identity);
            try {
                return new Client(socket, context, owned);
            } catch (RuntimeException | Error failure) {
                try {
                    socket.close();
                } catch (RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        public static @NonNull Client connectDealer(
                @NonNull ZContext context, @NonNull String address, @NonNull String identity) {
            return connect(context, address, identity, false);
        }

        public static @NonNull Client connect(@NonNull String address) {
            var context = new ZContext();
            try {
                return connect(context, address, UUID.randomUUID().toString(), true);
            } catch (RuntimeException | Error failure) {
                try {
                    context.close();
                } catch (RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
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

    public static final class Server extends ZmqChannel implements ServerTransport {
        private Server(ZMQ.@NonNull Socket socket, @NonNull ZContext context) {
            super(socket, context, false);
        }

        public static @NonNull Server bindRouter(
                @NonNull ZContext context, @NonNull String address) {
            var socket = open(context, SocketType.ROUTER, address, null);
            try {
                return new Server(socket, context);
            } catch (RuntimeException | Error failure) {
                try {
                    socket.close();
                } catch (RuntimeException | Error cleanup) {
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
