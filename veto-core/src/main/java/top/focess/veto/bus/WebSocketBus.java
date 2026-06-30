package top.focess.veto.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import top.focess.veto.model.DAGPayload;

/**
 * bus Communication Bus - WebSocket transport layer. Single umbilical to the Java Spring Boot cloud
 * backend. Handles bidirectional routing of DAG task payloads with heartbeat and reconnection.
 */
@Component
public class WebSocketBus extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WebSocketBus.class);

    private final @NonNull BusConfiguration config;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull HeartbeatManager heartbeatManager;
    private final @NonNull ReconnectionHandler reconnectionHandler;

    private volatile WebSocketSession session;
    private final Map<String, Consumer<DAGPayload>> dagRouteTable = new ConcurrentHashMap<>();
    private final Map<String, Consumer<String>> messageRouteTable = new ConcurrentHashMap<>();

    public WebSocketBus(
            BusConfiguration config,
            ObjectMapper objectMapper,
            HeartbeatManager heartbeatManager,
            ReconnectionHandler reconnectionHandler) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.heartbeatManager = heartbeatManager;
        this.reconnectionHandler = reconnectionHandler;
    }

    /** Connect to the cloud backend WebSocket endpoint. */
    public @NonNull CompletableFuture<Boolean> connect(@NonNull String backendUrl) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Thread.ofVirtual()
                .start(
                        () -> {
                            try {
                                StandardWebSocketClient client = new StandardWebSocketClient();
                                String wsUrl = backendUrl + config.getWebsocket().getPath();
                                log.info("Bus: Connecting to {} ...", wsUrl);
                                // doHandshake is deprecated-for-removal in Spring 6.x; we call
                                // Future.get() (the base interface method) on the virtual thread so
                                // the blocking call is cheap and no ListenableFuture chaining is
                                // required.
                                @SuppressWarnings("removal")
                                WebSocketSession wsSession = client.doHandshake(this, wsUrl).get();
                                this.session = wsSession;
                                log.info(
                                        "Bus: Connected successfully (session={})",
                                        wsSession.getId());
                                heartbeatManager.start(this);
                                future.complete(true);
                            } catch (Exception e) {
                                log.error("Bus: Connection failed", e);
                                reconnectionHandler.scheduleReconnect(this, backendUrl);
                                future.complete(false);
                            }
                        });
        return future;
    }

    /** Register a DAG payload route. */
    public void registerDAGRoute(@NonNull String taskType, @NonNull Consumer<DAGPayload> handler) {
        dagRouteTable.put(taskType, handler);
        log.debug("Bus: Registered DAG route for taskType={}", taskType);
    }

    /** Register a generic message route. */
    public void registerMessageRoute(
            @NonNull String messageType, @NonNull Consumer<String> handler) {
        messageRouteTable.put(messageType, handler);
        log.debug("Bus: Registered message route for type={}", messageType);
    }

    /** Send a DAG payload to the cloud backend. */
    public synchronized void sendDAGPayload(@NonNull DAGPayload payload) {
        if (!isConnected()) {
            log.warn("Bus: Cannot send DAG payload, not connected");
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            session.sendMessage(new TextMessage(json));
            log.debug(
                    "Bus: Sent DAG payload id={}, type={}", payload.getId(), payload.getTaskType());
        } catch (IOException e) {
            log.error("Bus: Failed to send DAG payload", e);
        }
    }

    /** Send a raw message. */
    public synchronized void sendMessage(@NonNull String message) {
        if (!isConnected()) {
            log.warn("Bus: Cannot send message, not connected");
            return;
        }
        try {
            session.sendMessage(new TextMessage(message));
        } catch (IOException e) {
            log.error("Bus: Failed to send message", e);
        }
    }

    @Override
    protected void handleTextMessage(
            @NonNull WebSocketSession session, @NonNull TextMessage message) {
        String payload = message.getPayload();
        try {
            DAGPayload dagPayload = objectMapper.readValue(payload, DAGPayload.class);
            Consumer<DAGPayload> handler = dagRouteTable.get(dagPayload.getTaskType());
            if (handler != null) {
                handler.accept(dagPayload);
            } else {
                log.warn("Bus: No route for taskType={}", dagPayload.getTaskType());
            }
        } catch (Exception e) {
            // Try generic message routing
            Consumer<String> fallback = messageRouteTable.get("fallback");
            if (fallback != null) {
                fallback.accept(payload);
            } else {
                log.warn("Bus: Unhandled message ({} bytes)", payload.length());
            }
        }
    }

    @Override
    public void afterConnectionClosed(
            @NonNull WebSocketSession session, @NonNull CloseStatus status) {
        log.warn(
                "Bus: Connection closed (code={}, reason={})",
                status.getCode(),
                status.getReason());
        this.session = null;
        heartbeatManager.stop();
        reconnectionHandler.scheduleReconnect(this, reconnectionHandler.getLastBackendUrl());
    }

    @Override
    public void handleTransportError(
            @NonNull WebSocketSession session, @NonNull Throwable exception) {
        log.error("Bus: Transport error", exception);
    }

    public boolean isConnected() {
        return session != null && session.isOpen();
    }

    public void disconnect() {
        heartbeatManager.stop();
        if (session != null && session.isOpen()) {
            try {
                session.close(CloseStatus.NORMAL);
            } catch (IOException e) {
                log.warn("Bus: Error during disconnect", e);
            }
        }
        this.session = null;
    }
}
