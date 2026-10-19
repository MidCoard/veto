package top.focess.veto.transport.tcp;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.api.util.Resources;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.contract.FrameTransport;

/**
 * Channel transport used by the terminal's ProtocolClient and the backend's IpcServer. Owns the
 * channel and its length-prefixed UTF-8 JSON frames; the four-byte big-endian length excludes the
 * header. Invalid or incomplete input is reported to the owner, which must end the connection.
 *
 * <p><b>Thread safety:</b> one reader, one writer and concurrent close. Each direction owns its
 * buffers and selector, so a stalled write does not block reads. The calling workers wait for IO
 * readiness with a deadline; this transport creates no threads, executor or output queue. Closing
 * the channel and selectors wakes both directions.
 */
public final class TcpConnection implements FrameTransport {
    private static final int IO_TIMEOUT_MILLIS = 10_000;
    private static final int IDLE_TIMEOUT_MILLIS = 90_000;
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.transport.tcp.TcpConnection");

    private final @NonNull SocketChannel channel;
    private final @NonNull Selector readable;
    private final @NonNull Selector writable;
    // Concurrent owner close releases all three resources once, even if the JDK closed the channel.
    private boolean closed;

    private TcpConnection(@NonNull SocketChannel channel, InetSocketAddress endpoint)
            throws IOException {
        this.channel = channel;
        Selector input = null;
        Selector output = null;
        try {
            if (endpoint != null) channel.socket().connect(endpoint, IO_TIMEOUT_MILLIS);
            if (!channel.isConnected()) throw new NotYetConnectedException();
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            channel.configureBlocking(false);
            input = Selector.open();
            channel.register(input, SelectionKey.OP_READ);
            output = Selector.open();
            channel.register(output, SelectionKey.OP_WRITE);
            readable = input;
            writable = output;
        } catch (Throwable failure) {
            if (output != null) Resources.closeOnFailure(output, failure);
            if (input != null) Resources.closeOnFailure(input, failure);
            Resources.closeOnFailure(channel, failure);
            throw failure;
        }
    }

    /** Opens an owned client channel, with a bounded connect and no implicit reconnect. */
    public static @NonNull TcpConnection connect(@NonNull String address) throws IOException {
        var endpoint = endpoint(address, false);
        return new TcpConnection(SocketChannel.open(), endpoint);
    }

    /** Takes ownership of an accepted channel, including cleanup if initialization fails. */
    public static @NonNull TcpConnection accept(@NonNull SocketChannel channel) throws IOException {
        return new TcpConnection(channel, null);
    }

    /** Binds a blocking listener; its caller owns closure. Port zero requests an ephemeral port. */
    public static @NonNull ServerSocketChannel bind(@NonNull String address) throws IOException {
        var endpoint = endpoint(address, true);
        if (endpoint.isUnresolved()) throw new UnknownHostException(endpoint.getHostString());
        var listener = ServerSocketChannel.open();
        try {
            return listener.bind(endpoint, 50);
        } catch (Throwable failure) {
            Resources.closeOnFailure(listener, failure);
            throw failure;
        }
    }

    private static @NonNull InetSocketAddress endpoint(@NonNull String address, boolean listener) {
        var uri = URI.create(address);
        var host = uri.getHost();
        var path = uri.getRawPath();
        if (!"tcp".equals(uri.getScheme())
                || host == null
                || uri.getPort() < (listener ? 0 : 1)
                || uri.getPort() > 65_535
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (path != null && !path.isEmpty()))
            throw new IllegalArgumentException("Expected tcp://host:port");
        return new InetSocketAddress(host, uri.getPort());
    }

    @Override
    public void send(@NonNull Frame frame) throws IOException {
        send(FrameCodec.encode(frame));
    }

    /**
     * Writes a complete encoded frame. Invalid lengths are rejected before IO; the connection is
     * still usable. An IO failure may leave a partial frame, so the owner must close instead of
     * retry.
     */
    public void send(byte @NonNull [] payload) throws IOException {
        if (payload.length == 0 || payload.length > MAX_FRAME_BYTES)
            throw new IOException("Protocol frame exceeds the allowed size");
        var buffer = ByteBuffer.allocate(Integer.BYTES + payload.length);
        buffer.putInt(payload.length).put(payload).flip();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(IO_TIMEOUT_MILLIS);
        while (buffer.hasRemaining()) {
            if (deadline - System.nanoTime() <= 0)
                throw new SocketTimeoutException("TCP write timed out");
            if (channel.write(buffer) == 0) awaitReady(writable, deadline);
        }
    }

    @Override
    public @NonNull Frame receive() throws IOException {
        var header = ByteBuffer.allocate(Integer.BYTES);
        header.limit(1);
        readFully(header, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(IDLE_TIMEOUT_MILLIS));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(IO_TIMEOUT_MILLIS);
        header.limit(Integer.BYTES);
        readFully(header, deadline);
        int length = header.flip().getInt();
        if (length <= 0 || length > MAX_FRAME_BYTES)
            throw new IOException("Invalid protocol frame length");
        var payload = ByteBuffer.allocate(length);
        readFully(payload, deadline);
        var frame = FrameCodec.decode(payload.array());
        if (frame == null) throw new IOException("Invalid protocol frame payload");
        return frame;
    }

    private void readFully(@NonNull ByteBuffer buffer, long deadline) throws IOException {
        while (buffer.hasRemaining()) {
            if (deadline - System.nanoTime() <= 0)
                throw new SocketTimeoutException("TCP read timed out");
            int read = channel.read(buffer);
            if (read < 0) throw new EOFException("Peer closed during a protocol frame");
            if (read == 0) awaitReady(readable, deadline);
        }
    }

    /** Waits on the caller's IO thread; a readiness notification may still require another try. */
    private void awaitReady(@NonNull Selector selector, long deadline) throws IOException {
        // Selector.select wakes on interruption without clearing it. End IO instead of spinning.
        if (Thread.currentThread().isInterrupted()) throw new ClosedByInterruptException();
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException("TCP IO timed out");
        try {
            if (selector.select(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))) > 0)
                selector.selectedKeys().clear();
        } catch (ClosedSelectorException closed) {
            // Concurrent close ends both selectors; expose the same IO contract as channel close.
            throw new ClosedChannelException();
        }
    }

    /** Idempotent; may be called while either direction waits for IO readiness. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (var resource : List.of(channel, readable, writable)) {
            try {
                resource.close();
            } catch (IOException failure) {
                log.debug("Could not close TCP resource ({})", failure.getClass().getSimpleName());
            }
        }
    }
}
