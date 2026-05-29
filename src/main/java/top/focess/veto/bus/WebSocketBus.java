package top.focess.veto.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.focess.veto.model.DAGPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * C3 Communication Bus  - WebSocket transport layer.
 * Single umbilical to the Java Spring Boot cloud backend.
 * Handles bidirectional routing of DAG task payloads with heartbeat and reconnection.
 */
@Component
public class WebSocketBus extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WebSocketBus.class);

    private final BusConfiguration config;
    private final ObjectMapper objectMapper;
    private final HeartbeatManager heartbeatManager;
    private final ReconnectionHandler reconnectionHandler;

    private volatile WebSocketSession session;
    private final Map<String, Consumer<DAGPayload>> dagRouteTable = new ConcurrentHashMap<>();
    private final Map<String, Consumer<String>> messageRouteTable = new ConcurrentHashMap<>();

    public WebSocketBus(BusConfiguration config, ObjectMapper objectMapper,
                        HeartbeatManager heartbeatManager, ReconnectionHandler reconnectionHandler) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.heartbeatManager = heartbeatManager;
        this.reconnectionHandler = reconnectionHandler;
    }

    /**
     * Connect to the cloud backend WebSocket endpoint.
     */
    public CompletableFuture<Boolean> connect(String backendUrl) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            StandardWebSocketClient client = new StandardWebSocketClient();
            String wsUrl = backendUrl + config.getWebsocket().getPath();
            log.info("C3 Bus: Connecting to {} ...", wsUrl);

            client.doHandshake(this, wsUrl)
                .addCallback(
                    wsSession -> {
                        this.session = wsSession;
                        log.info("C3 Bus: Connected successfully (session={})", wsSession.getId());
                        heartbeatManager.start(this);
                        future.complete(true);
                    },
                    ex -> {
                        log.error("C3 Bus: Connection failed", ex);
                        reconnectionHandler.scheduleReconnect(this, backendUrl);
                        future.complete(false);
                    }
                );
        } catch (Exception e) {
            log.error("C3 Bus: Connection error", e);
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Register a DAG payload route.
     */
    public void registerDAGRoute(String taskType, Consumer<DAGPayload> handler) {
        dagRouteTable.put(taskType, handler);
        log.debug("C3 Bus: Registered DAG route for taskType={}", taskType);
    }

    /**
     * Register a generic message route.
     */
    public void registerMessageRoute(String messageType, Consumer<String> handler) {
        messageRouteTable.put(messageType, handler);
        log.debug("C3 Bus: Registered message route for type={}", messageType);
    }

    /**
     * Send a DAG payload to the cloud backend.
     */
    public synchronized void sendDAGPayload(DAGPayload payload) {
        if (!isConnected()) {
            log.warn("C3 Bus: Cannot send DAG payload, not connected");
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            session.sendMessage(new TextMessage(json));
            log.debug("C3 Bus: Sent DAG payload id={}, type={}", payload.getId(), payload.getTaskType());
        } catch (IOException e) {
            log.error("C3 Bus: Failed to send DAG payload", e);
        }
    }

    /**
     * Send a raw message.
     */
    public synchronized void sendMessage(String message) {
        if (!isConnected()) {
            log.warn("C3 Bus: Cannot send message, not connected");
            return;
        }
        try {
            session.sendMessage(new TextMessage(message));
        } catch (IOException e) {
            log.error("C3 Bus: Failed to send message", e);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        try {
            DAGPayload dagPayload = objectMapper.readValue(payload, DAGPayload.class);
            Consumer<DAGPayload> handler = dagRouteTable.get(dagPayload.getTaskType());
            if (handler != null) {
                handler.accept(dagPayload);
            } else {
                log.warn("C3 Bus: No route for taskType={}", dagPayload.getTaskType());
            }
        } catch (Exception e) {
            // Try generic message routing
            Consumer<String> fallback = messageRouteTable.get("fallback");
            if (fallback != null) {
                fallback.accept(payload);
            } else {
                log.warn("C3 Bus: Unhandled message ({} bytes)", payload.length());
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("C3 Bus: Connection closed (code={}, reason={})", status.getCode(), status.getReason());
        this.session = null;
        heartbeatManager.stop();
        reconnectionHandler.scheduleReconnect(this, reconnectionHandler.getLastBackendUrl());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("C3 Bus: Transport error", exception);
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
                log.warn("C3 Bus: Error during disconnect", e);
            }
        }
        this.session = null;
    }
}
