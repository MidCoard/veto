package top.focess.veto.bus;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import top.focess.veto.VetoVersion;
import top.focess.veto.contract.DAGPayload;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.contract.ProtocolClient;
import top.focess.veto.transport.websocket.WebSocketChannel;

/** Application routes over the same protocol connection used by the ZeroMQ terminal. */
@Component
public class WebSocketBus {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.bus.WebSocketBus");
    private final @NonNull BusConfiguration config;
    private final @NonNull ReconnectionHandler reconnection;
    private final @NonNull AtomicLong generation = new AtomicLong();
    private final @NonNull Map<@NonNull String, @NonNull Consumer<DAGPayload>> dagRoutes =
            new ConcurrentHashMap<>();
    private final @NonNull Map<@NonNull String, @NonNull Consumer<String>> messageRoutes =
            new ConcurrentHashMap<>();
    private volatile Connection connection;

    public WebSocketBus(
            @NonNull BusConfiguration config, @NonNull ReconnectionHandler reconnection) {
        this.config = config;
        this.reconnection = reconnection;
    }

    /** A remote backend login token is required, just as for browser connections. */
    public synchronized @NonNull CompletableFuture<Boolean> connect(
            @NonNull String backendUrl, @NonNull String token) {
        disconnect();
        return startConnection(backendUrl, token, generation.get());
    }

    private synchronized @NonNull CompletableFuture<Boolean> startConnection(
            @NonNull String backendUrl, @NonNull String token, long epoch) {
        if (epoch != generation.get() || connection != null)
            return CompletableFuture.completedFuture(false);
        var attempt = new Connection();
        connection = attempt;
        var result = new CompletableFuture<Boolean>();
        Thread.ofVirtual()
                .name("bus-protocol-reader")
                .start(
                        () -> {
                            try {
                                var headers = new WebSocketHttpHeaders();
                                headers.add("X-Veto-Session-Token", token);
                                var sockets =
                                        new SockJsClient(
                                                List.of(
                                                        new WebSocketTransport(
                                                                new StandardWebSocketClient())));
                                sockets.execute(
                                                attempt,
                                                headers,
                                                URI.create(
                                                        backendUrl
                                                                + config.getWebsocket().getPath()))
                                        .get(10, TimeUnit.SECONDS);
                                if (epoch != generation.get()) return;
                                var transport = attempt.transport;
                                if (transport == null)
                                    throw new IllegalStateException(
                                            "WebSocket transport was not established");
                                var client =
                                        new ProtocolClient(
                                                transport,
                                                VetoVersion.VERSION,
                                                "",
                                                config.getWebsocket().getHeartbeatIntervalMs());
                                attempt.client = client;
                                if (epoch != generation.get()) return;
                                reconnection.reset();
                                result.complete(true);
                                while (epoch == generation.get() && !client.isClosed()) {
                                    var frame = client.receive(250, TimeUnit.MILLISECONDS);
                                    if (frame != null) route(frame);
                                }
                            } catch (Exception failure) {
                                // Socket handshake exceptions can contain credentials; log only the
                                // failure type.
                                log.warn(
                                        "Bus connection ended ({})",
                                        failure.getClass().getSimpleName());
                            } finally {
                                result.complete(false);
                                try {
                                    attempt.close();
                                } catch (RuntimeException cleanup) {
                                    log.warn(
                                            "Bus cleanup failed ({})",
                                            cleanup.getClass().getSimpleName());
                                }
                                synchronized (WebSocketBus.this) {
                                    if (connection == attempt) connection = null;
                                    if (epoch == generation.get())
                                        reconnection.scheduleReconnect(
                                                () -> startConnection(backendUrl, token, epoch));
                                }
                            }
                        });
        return result;
    }

    public void registerDAGRoute(@NonNull String taskType, @NonNull Consumer<DAGPayload> handler) {
        dagRoutes.put(taskType, handler);
    }

    public void registerMessageRoute(@NonNull String type, @NonNull Consumer<String> handler) {
        messageRoutes.put(type, handler);
    }

    public void sendDAGPayload(@NonNull DAGPayload payload) {
        sendMessage(new Frame.DagPayload(payload, null));
    }

    public void sendMessage(Frame.@NonNull ClientFrame frame) {
        var active = connection;
        var client = active == null ? null : active.client;
        if (client == null || client.isClosed())
            throw new IllegalStateException("Bus is not connected");
        client.send(frame);
    }

    private void route(Frame.@NonNull ServerFrame frame) {
        if (frame instanceof Frame.DagPayload dag) {
            var handler = dagRoutes.get(dag.data().getTaskType());
            if (handler != null) handler.accept(dag.data());
        } else {
            var handler = messageRoutes.get("fallback");
            if (handler != null) handler.accept(FrameCodec.encodeString(frame));
        }
    }

    public boolean isConnected() {
        var active = connection;
        var client = active == null ? null : active.client;
        return client != null && !client.isClosed();
    }

    public synchronized void disconnect() {
        generation.incrementAndGet();
        reconnection.reset();
        var active = connection;
        connection = null;
        if (active != null) active.close();
    }

    private static final class Connection extends TextWebSocketHandler {
        private volatile WebSocketChannel.Client transport;
        private volatile ProtocolClient client;
        private final @NonNull AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void afterConnectionEstablished(@NonNull WebSocketSession socket) {
            var channel =
                    new WebSocketChannel.Client(
                            text -> socket.sendMessage(new TextMessage(text)), socket::close);
            transport = channel;
            if (closed.get()) channel.close();
        }

        @Override
        protected void handleTextMessage(
                @NonNull WebSocketSession socket, @NonNull TextMessage message) {
            var channel = transport;
            if (channel != null && !channel.accept(message.getPayload()))
                log.warn("Bus rejected an invalid server frame");
        }

        @Override
        public void afterConnectionClosed(
                @NonNull WebSocketSession socket, @NonNull CloseStatus status) {
            var channel = transport;
            if (channel != null) channel.close();
        }

        @Override
        public void handleTransportError(
                @NonNull WebSocketSession socket, @NonNull Throwable failure) {
            var channel = transport;
            if (channel != null) channel.close();
        }

        private void close() {
            closed.set(true);
            var active = client;
            if (active != null) active.close();
            var channel = transport;
            if (channel != null) channel.close();
        }
    }
}
