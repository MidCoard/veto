package top.focess.veto.bus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.util.Resources;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;

/** Real native WebSocket peer for protocol tests; owns and joins its HTTP client on close. */
public final class WebSocketTestClient implements AutoCloseable {
    private final @NonNull HttpClient client = HttpClient.newHttpClient();
    private final @NonNull IncomingFrames incoming = new IncomingFrames();
    private final @NonNull WebSocket socket;

    private WebSocketTestClient(@NonNull String address, String token) throws Exception {
        try {
            var builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
            if (token != null) builder.header("X-Veto-Session-Token", token);
            socket =
                    Objects.requireNonNull(
                            builder.buildAsync(URI.create(address), incoming)
                                    .get(10, TimeUnit.SECONDS));
        } catch (Throwable failure) {
            Resources.closeOnFailure(client, failure);
            throw failure;
        }
    }

    public static @NonNull WebSocketTestClient connect(@NonNull String address) throws Exception {
        return new WebSocketTestClient(address, null);
    }

    public static @NonNull WebSocketTestClient connect(
            @NonNull String address, @NonNull String token) throws Exception {
        return new WebSocketTestClient(address, token);
    }

    public void send(@NonNull Frame frame) {
        sendText(FrameCodec.encodeString(frame));
    }

    public void sendText(@NonNull String frame) {
        socket.sendText(frame, true).join();
    }

    public @NonNull Frame receive() throws Exception {
        var item = incoming.frames.poll(10, TimeUnit.SECONDS);
        if (item instanceof Frame frame) return frame;
        if (item instanceof IOException failure) throw failure;
        if (item instanceof Throwable failure) throw new IOException("WebSocket failed", failure);
        throw new AssertionError("Timed out waiting for a protocol frame");
    }

    /** Stops requesting further native delivery, so the real socket applies backpressure. */
    public void pauseReads() {
        incoming.paused = true;
    }

    @Override
    public void close() {
        socket.abort();
        client.close();
    }

    /** Owns callback assembly and delivery demand before the socket has finished connecting. */
    private static final class IncomingFrames implements WebSocket.Listener {
        private final @NonNull LinkedBlockingQueue<@NonNull Object> frames =
                new LinkedBlockingQueue<>();
        private final @NonNull StringBuilder text = new StringBuilder();
        private volatile boolean paused;

        @Override
        public @NonNull CompletionStage<?> onText(
                @NonNull WebSocket webSocket, @NonNull CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                var frame = FrameCodec.decode(text.toString());
                frames.add(frame == null ? new IOException("Invalid server frame") : frame);
                text.setLength(0);
            }
            if (!paused) webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public @NonNull CompletionStage<?> onClose(
                @NonNull WebSocket webSocket, int statusCode, @NonNull String reason) {
            frames.add(new IOException("WebSocket closed: " + statusCode));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(@NonNull WebSocket webSocket, @NonNull Throwable error) {
            frames.add(error);
        }
    }
}
