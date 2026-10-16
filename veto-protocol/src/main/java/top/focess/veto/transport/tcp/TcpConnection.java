package top.focess.veto.transport.tcp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.api.util.Resources;
import top.focess.veto.api.util.Threads;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.contract.FrameTransport;

/**
 * Owns a standard TCP socket and its length-prefixed UTF-8 JSON frames. The four-byte big-endian
 * length excludes the header. An invalid or incomplete frame ends the connection.
 *
 * <p><b>Thread safety:</b> follows {@link FrameTransport}: one reader, one writer and concurrent
 * close. Read and write have separate stream state. Callers own their output queues; this class
 * neither queues frames nor creates IO workers. Closing the socket releases blocked IO.
 */
public final class TcpConnection implements FrameTransport {
    private static final int IO_TIMEOUT_MILLIS = 10_000;
    private static final int IDLE_TIMEOUT_MILLIS = 90_000;
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.transport.tcp.TcpConnection");
    private static final @NonNull ScheduledThreadPoolExecutor WRITE_DEADLINES =
            Threads.newDeadlineScheduler("tcp-write-deadline");
    private final @NonNull Socket socket;
    private final @NonNull InputStream input;
    private final @NonNull DataOutputStream output;

    private TcpConnection(@NonNull Socket socket, InetSocketAddress endpoint) throws IOException {
        this.socket = socket;
        try {
            if (endpoint != null) socket.connect(endpoint, IO_TIMEOUT_MILLIS);
            socket.setTcpNoDelay(true);
            input = new BufferedInputStream(socket.getInputStream());
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        } catch (Throwable failure) {
            Resources.closeOnFailure(socket, failure);
            throw failure;
        }
    }

    /** Opens an owned client socket, with a bounded connect and no implicit reconnect. */
    public static @NonNull TcpConnection connect(@NonNull String address) throws IOException {
        var endpoint = endpoint(address, false);
        return new TcpConnection(new Socket(), endpoint);
    }

    /** Takes ownership of an accepted socket, including cleanup if stream setup fails. */
    public static @NonNull TcpConnection accept(@NonNull Socket socket) throws IOException {
        return new TcpConnection(socket, null);
    }

    /** Binds a listener; its caller owns closure. Port zero requests an ephemeral port. */
    public static @NonNull ServerSocket bind(@NonNull String address) throws IOException {
        var endpoint = endpoint(address, true);
        var host = endpoint.getAddress();
        if (host == null) throw new UnknownHostException(endpoint.getHostString());
        return new ServerSocket(endpoint.getPort(), 50, host);
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

    /** Writes an already encoded frame. The caller must keep its bytes unchanged until return. */
    public void send(byte @NonNull [] payload) throws IOException {
        if (payload.length == 0 || payload.length > MAX_FRAME_BYTES)
            throw new IOException("Protocol frame exceeds the allowed size");
        // SO_TIMEOUT bounds reads only. Closing on this deadline also releases a blocked write.
        var deadline =
                WRITE_DEADLINES.schedule(this::close, IO_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        try {
            output.writeInt(payload.length);
            output.write(payload);
            output.flush();
        } finally {
            if (!deadline.cancel(false)) log.debug("TCP write deadline already elapsed");
        }
    }

    @Override
    public @NonNull Frame receive() throws IOException {
        socket.setSoTimeout(IDLE_TIMEOUT_MILLIS);
        int first = input.read();
        if (first < 0) throw new EOFException("Peer closed the connection");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(IO_TIMEOUT_MILLIS);
        var header = new byte[4];
        header[0] = (byte) first;
        readFully(header, 1, 3, deadline);
        int length =
                ((header[0] & 0xff) << 24)
                        | ((header[1] & 0xff) << 16)
                        | ((header[2] & 0xff) << 8)
                        | (header[3] & 0xff);
        if (length <= 0 || length > MAX_FRAME_BYTES)
            throw new IOException("Invalid protocol frame length");
        var payload = new byte[length];
        readFully(payload, 0, length, deadline);
        var frame = FrameCodec.decode(payload);
        if (frame == null) throw new IOException("Invalid protocol frame payload");
        return frame;
    }

    private void readFully(byte @NonNull [] bytes, int offset, int length, long deadline)
            throws IOException {
        int end = offset + length;
        while (offset < end) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new SocketTimeoutException("Protocol frame timed out");
            socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            int read = input.read(bytes, offset, end - offset);
            if (read < 0) throw new EOFException("Peer closed during a protocol frame");
            offset += read;
        }
    }

    /** Idempotent; may be called while either direction is blocked. */
    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException failure) {
            log.debug("Could not close TCP socket ({})", failure.getClass().getSimpleName());
        }
    }
}
