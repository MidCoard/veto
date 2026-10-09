package top.focess.veto.transport.websocket;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import top.focess.veto.contract.ClientTransport;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.contract.ServerTransport;
import top.focess.veto.contract.Transport;

/** Adapts one WebSocket's text callbacks to the shared framed transport contract. */
public abstract class WebSocketChannel {
    @FunctionalInterface
    public interface TextSender {
        void send(@NonNull String text) throws IOException;
    }

    @FunctionalInterface
    public interface SocketCloser {
        void close() throws IOException;
    }

    private sealed interface Inbound permits Message, End {}

    private record Message(Transport.@NonNull FramedMsg value) implements Inbound {}

    private enum End implements Inbound {
        CLOSED
    }

    private final @NonNull String identity;
    private final @NonNull TextSender sender;
    private final @NonNull SocketCloser closer;
    private final @NonNull BlockingQueue<@NonNull Inbound> incoming = new ArrayBlockingQueue<>(256);
    private final @NonNull AtomicBoolean closed = new AtomicBoolean();

    private WebSocketChannel(
            @NonNull String identity, @NonNull TextSender sender, @NonNull SocketCloser closer) {
        this.identity = identity;
        this.sender = sender;
        this.closer = closer;
    }

    /** Called by the socket listener. Invalid or wrong-direction frames are rejected. */
    public boolean accept(@NonNull String text) {
        if (closed.get()) return false;
        Frame frame = FrameCodec.decode(text);
        if (frame == null
                || (this instanceof Client
                        ? !(frame instanceof Frame.ServerFrame)
                        : !(frame instanceof Frame.ClientFrame))) return false;
        if (!incoming.offer(new Message(new Transport.FramedMsg(identity, frame)))) {
            close();
            throw new IllegalStateException("WebSocket receive queue is full");
        }
        return true;
    }

    public Transport.FramedMsg recv(long timeoutMillis) {
        if (closed.get()) throw new IllegalStateException("WebSocket is closed");
        try {
            Inbound next =
                    timeoutMillis < 0
                            ? incoming.take()
                            : incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            if (next instanceof Message message) return message.value();
            if (next == End.CLOSED) throw new IllegalStateException("WebSocket is closed");
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("WebSocket receive interrupted", interrupted);
        }
    }

    protected void sendFrame(@NonNull Frame frame) {
        if (closed.get()) throw new IllegalStateException("WebSocket is closed");
        try {
            sender.send(FrameCodec.encodeString(frame));
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** Wakes blocked receivers; the socket closer runs at most once. */
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        incoming.clear();
        if (!incoming.offer(End.CLOSED))
            throw new IllegalStateException("Cannot signal closed WebSocket");
        try {
            closer.close();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    public static final class Client extends WebSocketChannel implements ClientTransport {
        public Client(@NonNull TextSender sender, @NonNull SocketCloser closer) {
            super("", sender, closer);
        }

        @Override
        public void send(Frame.@NonNull ClientFrame frame) {
            sendFrame(frame);
        }
    }

    public static final class Server extends WebSocketChannel implements ServerTransport {
        private final @NonNull String peer;

        public Server(
                @NonNull String peer, @NonNull TextSender sender, @NonNull SocketCloser closer) {
            super(peer, sender, closer);
            this.peer = peer;
        }

        @Override
        public void send(@NonNull String identity, @NonNull Frame frame) {
            if (!peer.equals(identity))
                throw new IllegalArgumentException("Unknown WebSocket peer");
            if (!(frame instanceof Frame.ServerFrame))
                throw new IllegalArgumentException("Expected server frame");
            sendFrame(frame);
        }
    }
}
