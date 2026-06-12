package top.focess.veto.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
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

    private static final Logger log = LoggerFactory.getLogger(VetoWebSocketHandler.class);

    private final ObjectMapper objectMapper;
    private final VetoGateway vetoGateway;

    private final CopyOnWriteArrayList<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, String> sessionRoutes = new ConcurrentHashMap<>();

    private final AtomicLong messageCounter = new AtomicLong(0);

    public VetoWebSocketHandler(ObjectMapper objectMapper, VetoGateway vetoGateway) {
        this.objectMapper = objectMapper;
        this.vetoGateway = vetoGateway;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
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
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        long seq = messageCounter.incrementAndGet();

        log.debug(
                "WS Bus: Received msg #{} from '{}' ({} bytes)",
                seq,
                session.getId(),
                payload.length());

        try {
            Map<String, Object> msg = objectMapper.readValue(payload, Map.class);
            String type = (String) msg.getOrDefault("type", "");

            switch (type) {
                case "heartbeat" -> handleHeartbeat(session, msg, seq);
                case "dag.payload" -> handleDAGPayload(session, msg, seq);
                case "veto.process" -> handleVetoProcess(session, msg, seq);
                case "subscribe" -> handleSubscribe(session, msg);
                case "unsubscribe" -> handleUnsubscribe(session, msg);
                default -> handleUnknownType(session, payload, seq);
            }
        } catch (Exception e) {
            log.warn(
                    "WS Bus: Failed to parse message from '{}': {}",
                    session.getId(),
                    e.getMessage());
            sendJson(
                    session,
                    Map.of(
                            "type",
                            "error",
                            "message",
                            "Invalid message format: " + e.getMessage(),
                            "seq",
                            seq));
        }
    }

    private void handleHeartbeat(WebSocketSession session, Map<String, Object> msg, long seq) {
        sendJson(
                session,
                Map.of(
                        "type", "heartbeat_ack",
                        "seq", msg.getOrDefault("seq", seq),
                        "timestamp", Instant.now().toString()));
    }

    private void handleDAGPayload(WebSocketSession session, Map<String, Object> msg, long seq) {
        String taskType = (String) msg.getOrDefault("taskType", "unknown");
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

    private void handleVetoProcess(WebSocketSession session, Map<String, Object> msg, long seq) {
        String rawPayload = (String) msg.getOrDefault("payload", "");
        if (rawPayload.isEmpty()) {
            sendJson(
                    session,
                    Map.of(
                            "type", "error",
                            "message", "payload field is required",
                            "seq", seq));
            return;
        }

        String dagPayloadId = (String) msg.getOrDefault("dagPayloadId", "ws-" + seq);
        String requestId = (String) msg.getOrDefault("requestId", "ws-req-" + seq);
        String componentSource = (String) msg.getOrDefault("componentSource", "WS-Client");

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

    private void handleSubscribe(WebSocketSession session, Map<String, Object> msg) {
        String topic = (String) msg.getOrDefault("topic", "all");
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

    private void handleUnsubscribe(WebSocketSession session, Map<String, Object> msg) {
        sessionRoutes.remove(session.getId());
        sendJson(session, Map.of("type", "unsubscribed", "timestamp", Instant.now().toString()));
    }

    private void handleUnknownType(WebSocketSession session, String payload, long seq) {
        log.debug(
                "WS Bus: Unknown message type from '{}' ({} bytes)",
                session.getId(),
                payload.length());
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
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        sessionRoutes.remove(session.getId());
        log.info(
                "WS Bus: Client '{}' disconnected (code={}, reason='{}')",
                session.getId(),
                status.getCode(),
                status.getReason());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WS Bus: Transport error for '{}': {}", session.getId(), exception.getMessage());
        sessions.remove(session);
        sessionRoutes.remove(session.getId());
    }

    /** Broadcast a message to all connected clients except the sender. */
    public void broadcast(Map<String, Object> message, String excludeSessionId) {
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.warn("WS Bus: Failed to serialize broadcast", e);
            return;
        }

        for (WebSocketSession s : sessions) {
            if (s.isOpen() && !s.getId().equals(excludeSessionId)) {
                try {
                    s.sendMessage(new TextMessage(json));
                } catch (IOException e) {
                    log.warn("WS Bus: Failed to send broadcast to '{}'", s.getId(), e);
                }
            }
        }
    }

    /** Send a DAGPayload result to all connected clients. */
    public void streamDAGResult(DAGPayload payload) {
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
    public void streamVetoResult(VetoGateway.VetoResult result, String dagPayloadId) {
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

    private void broadcastRaw(String json) {
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

    private void sendJson(WebSocketSession session, Map<String, Object> data) {
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
}
