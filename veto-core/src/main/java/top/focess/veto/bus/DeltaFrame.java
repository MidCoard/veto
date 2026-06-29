package top.focess.veto.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Part 8 Delta-frame broker abstraction. A {@code DeltaFrame} is a single streaming update
 * emitted by the agent loop and consumed by a transport (terminal, REST client, WebSocket session).
 * Frames are ordered by {@code sequence} within a session.
 *
 * <p>Frame kinds:
 *
 * <ul>
 *   <li>{@link Kind#ASSISTANT_THOUGHT} — the agent's emitted thought (interim, can be hidden for
 *       terse UIs)
 *   <li>{@link Kind#ASSISTANT_MESSAGE} — the agent's user-facing message (final or interim)
 *   <li>{@link Kind#TOOL_CALL} — the agent is about to call a tool
 *   <li>{@link Kind#TOOL_RESULT} — the tool returned (DATA — framed as data, not instructions)
 *   <li>{@link Kind#COMPACTION} — the session compacted
 *   <li>{@link Kind#BREAKER_TRIPPED} — the per-episode call ceiling tripped
 *   <li>{@link Kind#ERROR} — agent / tool / transport error
 * </ul>
 *
 * <p>The frame is JSON-serializable so transports can forward it as-is. The {@link WebSocketBus}
 * already exists as the production transport; a {@code DeltaBroker} sits between the agent loop and
 * the bus, multiplexing frames per session.
 */
public record DeltaFrame(
        UUID sessionId,
        long sequence,
        Instant emittedAt,
        Kind kind,
        String text,
        Map<String, JsonNode> attrs) {

    public enum Kind {
        ASSISTANT_THOUGHT,
        ASSISTANT_MESSAGE,
        TOOL_CALL,
        TOOL_RESULT,
        COMPACTION,
        BREAKER_TRIPPED,
        ERROR
    }

    public DeltaFrame {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId");
        }
        if (emittedAt == null) {
            emittedAt = Instant.now();
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind");
        }
        if (text == null) {
            text = "";
        }
        if (attrs == null) {
            attrs = Map.of();
        } else {
            attrs = Map.copyOf(attrs);
        }
    }

    /** Serialize to JSON via the shared Jackson {@link ObjectMapper}. */
    public String toJson(ObjectMapper mapper) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", sessionId.toString());
            payload.put("sequence", sequence);
            payload.put("emittedAt", emittedAt.toString());
            payload.put("kind", kind.name());
            payload.put("text", text);
            payload.put("attrs", attrs);
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("DeltaFrame serialization failed", e);
        }
    }

    /** Parse a JSON payload back into a {@code DeltaFrame}. */
    public static DeltaFrame fromJson(ObjectMapper mapper, String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return new DeltaFrame(
                    UUID.fromString(node.path("sessionId").asText()),
                    node.path("sequence").asLong(),
                    Instant.parse(node.path("emittedAt").asText()),
                    Kind.valueOf(node.path("kind").asText()),
                    node.path("text").asText(""),
                    mapper.convertValue(
                            node.path("attrs"),
                            new com.fasterxml.jackson.core.type.TypeReference<
                                    Map<String, JsonNode>>() {}));
        } catch (Exception e) {
            throw new RuntimeException("DeltaFrame parse failed", e);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Convenience builder. */
    public static final class Builder {
        private UUID sessionId;
        private long sequence;
        private Kind kind;
        private String text = "";
        private final Map<String, JsonNode> attrs = new LinkedHashMap<>();

        public Builder sessionId(UUID v) {
            this.sessionId = v;
            return this;
        }

        public Builder sequence(long v) {
            this.sequence = v;
            return this;
        }

        public Builder kind(Kind v) {
            this.kind = v;
            return this;
        }

        public Builder text(String v) {
            this.text = v;
            return this;
        }

        public Builder attr(String key, JsonNode value) {
            this.attrs.put(key, value);
            return this;
        }

        public DeltaFrame build() {
            return new DeltaFrame(sessionId, sequence, Instant.now(), kind, text, attrs);
        }
    }
}
