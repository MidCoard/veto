package top.focess.veto.bus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import top.focess.veto.model.DAGPayload;
import top.focess.veto.veto.VetoGateway;

/**
 * Server-side WebSocket handler for the Veto Bus (/ws/veto/bus). Clients (UI, MCP servers, workers)
 * connect here for real-time payload streaming. Routes DAG payloads, streams veto results, and
 * handles heartbeats.
 */
@Component
public class VetoWebSocketHandler extends TextWebSocketHandler {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.bus.VetoWebSocketHandler");

    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull VetoGateway vetoGateway;

    private final @NonNull CopyOnWriteArrayList<@NonNull WebSocketSession> sessions =
            new CopyOnWriteArrayList<>();
    private final @NonNull ConcurrentHashMap<@NonNull String, @NonNull String> sessionRoutes =
            new ConcurrentHashMap<>();

    private final @NonNull AtomicLong messageCounter = new AtomicLong(0);

    public VetoWebSocketHandler(
            @NonNull ObjectMapper objectMapper, @NonNull VetoGateway vetoGateway) {
        this.objectMapper = objectMapper;
        this.vetoGateway = vetoGateway;
    }

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        sessions.add(session);
        URI uri = session.getUri();
        if (uri != null) {
            String query = uri.getQuery();
            if (query != null && query.contains("route=")) {
                String route = query.replaceAll(".*route=([^&]+).*", "$1");
                sessionRoutes.put(session.getId(), route);
                log.info("WS Bus: Client '{}' connected with route='{}'", session.getId(), route);
            } else {
                log.info("WS Bus: Client '{}' connected (no route)", session.getId());
            }
        } else {
            log.info("WS Bus: Client '{}' connected", session.getId());
        }

        try {
            String welcome =
                    objectMapper.writeValueAsString(
                            Map.of(
                                    "type",
                                    "welcome",
                                    "sessionId",
                                    session.getId(),
                                    "timestamp",
                                    Instant.now().toString(),
                                    "version",
                                    "1.0.0-SNAPSHOT"));
            session.sendMessage(new TextMessage(welcome));
        } catch (IOException e) {
            log.warn("WS Bus: Failed to send welcome to '{}'", session.getId(), e);
        }
    }

    @Override
    protected void handleTextMessage(
            @NonNull WebSocketSession session, @NonNull TextMessage message) {
        String payload = message.getPayload();
        long seq = messageCounter.incrementAndGet();

        Map<@NonNull String, Object> msg;
        try {
            msg = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn(
                    "WS Bus: Failed to parse message from '{}': {}",
                    session.getId(),
                    String.valueOf(e.getMessage()));
            sendJson(
                    session,
                    Map.of(
                            "type",
                            "error",
                            "message",
                            "Invalid message format: " + e.getMessage(),
                            "seq",
                            seq));
            return;
        }
        if (msg == null) {
            sendJson(session, Map.of("type", "error", "message", "Message must be an object"));
            return;
        }

        String type = stringValue(msg, "type", "");
        // Log the message TYPE — that is the actionable fact. Heartbeats arrive once per second
        // per client and would drown every meaningful line, so they drop to TRACE; everything
        // else keeps a single concise DEBUG line.
        if ("heartbeat".equals(type)) {
            log.trace("WS Bus: heartbeat from '{}' seq={}", session.getId(), seq);
        } else {
            log.debug("WS Bus: msg #{} type='{}' from '{}'", seq, type, session.getId());
        }

        switch (type) {
            case "heartbeat" -> handleHeartbeat(session, msg, seq);
            case "dag.payload" -> handleDAGPayload(session, msg, seq);
            case "veto.process" -> handleVetoProcess(session, msg, seq);
            case "subscribe" -> handleSubscribe(session, msg);
            case "unsubscribe" -> handleUnsubscribe(session, msg);
            default -> handleUnknownType(session, payload, seq);
        }
    }

    private void handleHeartbeat(
            @NonNull WebSocketSession session,
            @NonNull Map<@NonNull String, Object> msg,
            long seq) {
        Object suppliedSequence = msg.get("seq");
        Object responseSequence = suppliedSequence == null ? seq : suppliedSequence;
        sendJson(
                session,
                Map.of(
                        "type",
                        "heartbeat_ack",
                        "seq",
                        responseSequence,
                        "timestamp",
                        Instant.now().toString()));
    }

    private void handleDAGPayload(
            @NonNull WebSocketSession session,
            @NonNull Map<@NonNull String, Object> msg,
            long seq) {
        String taskType = stringValue(msg, "taskType", "unknown");
        log.info("WS Bus: DAG payload from '{}' - type={}, seq={}", session.getId(), taskType, seq);

        sendJson(
                session,
                Map.of(
                        "type",
                        "dag.received",
                        "taskType",
                        taskType,
                        "seq",
                        seq,
                        "timestamp",
                        Instant.now().toString()));

        broadcast(
                Map.of(
                        "type",
                        "dag.payload",
                        "source",
                        session.getId(),
                        "taskType",
                        taskType,
                        "data",
                        msg,
                        "timestamp",
                        Instant.now().toString()),
                session.getId());
    }

    private void handleVetoProcess(
            @NonNull WebSocketSession session,
            @NonNull Map<@NonNull String, Object> msg,
            long seq) {
        String rawPayload = stringValue(msg, "payload", "");
        if (rawPayload.isEmpty()) {
            sendJson(
                    session,
                    Map.of(
                            "type", "error",
                            "message", "payload field is required",
                            "seq", seq));
            return;
        }

        String dagPayloadId = stringValue(msg, "dagPayloadId", "ws-" + seq);
        String requestId = stringValue(msg, "requestId", "ws-req-" + seq);
        String componentSource = stringValue(msg, "componentSource", "WS-Client");

        log.info(
                "WS Bus: Veto processing from '{}' - payload={} bytes",
                session.getId(),
                rawPayload.length());

        VetoGateway.VetoResult result =
                vetoGateway.processOutbound(rawPayload, dagPayloadId, requestId, componentSource);

        sendJson(
                session,
                Map.of(
                        "type", "veto.result",
                        "seq", seq,
                        "decision", result.decision().name(),
                        "processedPayload", result.processedPayload(),
                        "reason", result.reason(),
                        "redactionCount", result.redactionCount(),
                        "allowed", result.isAllowed(),
                        "timestamp", Instant.now().toString()));
    }

    private void handleSubscribe(
            @NonNull WebSocketSession session, @NonNull Map<@NonNull String, Object> msg) {
        String topic = stringValue(msg, "topic", "all");
        sessionRoutes.put(session.getId(), "sub:" + topic);
        log.info("WS Bus: Client '{}' subscribed to topic '{}'", session.getId(), topic);
        sendJson(
                session,
                Map.of(
                        "type",
                        "subscribed",
                        "topic",
                        topic,
                        "timestamp",
                        Instant.now().toString()));
    }

    private void handleUnsubscribe(
            @NonNull WebSocketSession session, @NonNull Map<@NonNull String, Object> msg) {
        sessionRoutes.remove(session.getId());
        sendJson(session, Map.of("type", "unsubscribed", "timestamp", Instant.now().toString()));
    }

    private void handleUnknownType(
            @NonNull WebSocketSession session, @NonNull String payload, long seq) {
        log.debug(
                "WS Bus: Unknown message type from '{}', echoing payload head: {}",
                session.getId(),
                payload.length() > 160 ? payload.substring(0, 160) + "…" : payload);
        sendJson(
                session,
                Map.of(
                        "type",
                        "echo",
                        "data",
                        payload,
                        "seq",
                        seq,
                        "timestamp",
                        Instant.now().toString()));
    }

    @Override
    public void afterConnectionClosed(
            @NonNull WebSocketSession session, @NonNull CloseStatus status) {
        sessions.remove(session);
        sessionRoutes.remove(session.getId());
        log.info(
                "WS Bus: Client '{}' disconnected (code={}, reason='{}')",
                session.getId(),
                status.getCode(),
                String.valueOf(status.getReason()));
    }

    @Override
    public void handleTransportError(
            @NonNull WebSocketSession session, @NonNull Throwable exception) {
        log.error(
                "WS Bus: Transport error for '{}': {}",
                session.getId(),
                String.valueOf(exception.getMessage()));
        sessions.remove(session);
        sessionRoutes.remove(session.getId());
    }

    /** Broadcast a message to all connected clients except the sender. */
    public void broadcast(@NonNull Map<String, Object> message, @NonNull String excludeSessionId) {
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.warn("WS Bus: Failed to serialize broadcast", e);
            return;
        }

        for (WebSocketSession s : sessions) {
            String route = sessionRoutes.get(s.getId());
            Object messageType = message.get("type");
            boolean acceptsRoute =
                    route == null
                            || "all".equals(route)
                            || "sub:all".equals(route)
                            || route.equals(messageType)
                            || route.equals("sub:" + messageType);
            if (s.isOpen() && !s.getId().equals(excludeSessionId) && acceptsRoute) {
                try {
                    s.sendMessage(new TextMessage(json));
                } catch (IOException e) {
                    log.warn("WS Bus: Failed to send broadcast to '{}'", s.getId(), e);
                }
            }
        }
    }

    /** Send a DAGPayload result to all connected clients. */
    public void streamDAGResult(@NonNull DAGPayload payload) {
        try {
            String json =
                    objectMapper.writeValueAsString(
                            Map.of(
                                    "type",
                                    "dag.result",
                                    "payload",
                                    payload,
                                    "timestamp",
                                    Instant.now().toString()));
            broadcastRaw(json);
        } catch (Exception e) {
            log.warn("WS Bus: Failed to stream DAG result", e);
        }
    }

    /** Stream a veto result to all connected clients. */
    public void streamVetoResult(
            VetoGateway.@NonNull VetoResult result, @NonNull String dagPayloadId) {
        try {
            String json =
                    objectMapper.writeValueAsString(
                            Map.of(
                                    "type", "veto.stream",
                                    "dagPayloadId", dagPayloadId,
                                    "decision", result.decision().name(),
                                    "redactionCount", result.redactionCount(),
                                    "reason", result.reason(),
                                    "timestamp", Instant.now().toString()));
            broadcastRaw(json);
        } catch (Exception e) {
            log.warn("WS Bus: Failed to stream veto result", e);
        }
    }

    /**
     * Broadcast a raw JSON string to all connected clients. Public so the {@link DeltaBusBridge}
     * can forward serialized {@link DeltaFrame}s to every connected client.
     */
    public void broadcastRaw(@NonNull String json) {
        for (WebSocketSession s : sessions) {
            if (s.isOpen()) {
                try {
                    s.sendMessage(new TextMessage(json));
                } catch (IOException e) {
                    log.warn("WS Bus: Failed to broadcast to '{}'", s.getId(), e);
                }
            }
        }
    }

    private void sendJson(
            @NonNull WebSocketSession session, @NonNull Map<@NonNull String, Object> data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.warn("WS Bus: Failed to send to '{}'", session.getId(), e);
        }
    }

    public int getActiveSessionCount() {
        return (int) sessions.stream().filter(WebSocketSession::isOpen).count();
    }

    public long getTotalMessages() {
        return messageCounter.get();
    }

    private static @NonNull String stringValue(
            @NonNull Map<@NonNull String, Object> message,
            @NonNull String key,
            @NonNull String fallback) {
        Object value = message.get(key);
        return value instanceof String stringValue ? stringValue : fallback;
    }
}
