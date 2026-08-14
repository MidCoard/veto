package top.focess.veto.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

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
        @NonNull UUID sessionId,
        long sequence,
        Instant emittedAt,
        @NonNull Kind kind,
        String text,
        Map<String, JsonNode> attrs) {

    public enum Kind {
        ASSISTANT_THOUGHT,
        ASSISTANT_MESSAGE,
        TOOL_CALL,
        TOOL_RESULT,
        COMPACTION,
        BREAKER_TRIPPED,
        ERROR,
        /** A HITL veto was raised and is waiting for the user's decision. */
        VETO_REQUIRED,
        /** A previously raised veto was resolved (approved/declined/edited). */
        VETO_RESOLVED,
        /** A background task (run_task) was launched. */
        TASK_STARTED,
        /** A background task finished (natural exit, auto-kill, or explicit stop). */
        TASK_EXITED,
        /** The episode finished; carries the final success flag so clients can stop waiting. */
        EPISODE_DONE
    }

    public DeltaFrame {
        if (emittedAt == null) {
            emittedAt = Instant.now();
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

    /** The canonical constructor normalizes a missing timestamp before publication. */
    @Override
    public @NonNull Instant emittedAt() {
        Instant value = emittedAt;
        if (value == null) {
            throw new IllegalStateException("DeltaFrame timestamp was not normalized");
        }
        return value;
    }

    /** The canonical constructor normalizes missing text to an empty string. */
    @Override
    public @NonNull String text() {
        String value = text;
        if (value == null) {
            throw new IllegalStateException("DeltaFrame text was not normalized");
        }
        return value;
    }

    /** The canonical constructor normalizes missing attributes to an immutable empty map. */
    @Override
    public @NonNull Map<@NonNull String, @NonNull JsonNode> attrs() {
        Map<@NonNull String, @NonNull JsonNode> value = attrs;
        if (value == null) {
            throw new IllegalStateException("DeltaFrame attributes were not normalized");
        }
        return value;
    }

    /** Serialize to JSON via the shared Jackson {@link ObjectMapper}. */
    public @NonNull String toJson(@NonNull ObjectMapper mapper) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", sessionId.toString());
            payload.put("sequence", sequence);
            payload.put("emittedAt", emittedAt().toString());
            payload.put("kind", kind.name());
            payload.put("text", text());
            payload.put("attrs", attrs());
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new RuntimeException("DeltaFrame serialization failed", e);
        }
    }

    /** Parse a JSON payload back into a {@code DeltaFrame}. */
    public static @NonNull DeltaFrame fromJson(@NonNull ObjectMapper mapper, @NonNull String json) {
        try {
            JsonNode node = mapper.readTree(json);
            Kind parsedKind =
                    top.focess.veto.util.Nullness.requireNonNull(
                            Kind.valueOf(node.path("kind").asText()), "DeltaFrame kind is missing");
            return new DeltaFrame(
                    UUID.fromString(node.path("sessionId").asText()),
                    node.path("sequence").asLong(),
                    Instant.parse(node.path("emittedAt").asText()),
                    parsedKind,
                    node.path("text").asText(""),
                    mapper.convertValue(
                            node.path("attrs"),
                            new com.fasterxml.jackson.core.type.TypeReference<
                                    Map<String, JsonNode>>() {}));
        } catch (Exception e) {
            throw new RuntimeException("DeltaFrame parse failed", e);
        }
    }

    public static @NonNull Builder builder() {
        return new Builder();
    }

    /** Convenience builder. */
    public static final class Builder {
        private UUID sessionId;
        private long sequence;
        private Kind kind;
        private @NonNull String text = "";
        private final @NonNull Map<String, JsonNode> attrs = new LinkedHashMap<>();

        public @NonNull Builder sessionId(@NonNull UUID v) {
            this.sessionId = v;
            return this;
        }

        public @NonNull Builder sequence(long v) {
            this.sequence = v;
            return this;
        }

        public @NonNull Builder kind(@NonNull Kind v) {
            this.kind = v;
            return this;
        }

        public @NonNull Builder text(@NonNull String v) {
            this.text = v;
            return this;
        }

        public @NonNull Builder attr(@NonNull String key, @NonNull JsonNode value) {
            this.attrs.put(key, value);
            return this;
        }

        /** Convenience: a string attr. */
        public @NonNull Builder attr(@NonNull String key, @NonNull String value) {
            return attr(key, TextNode.valueOf(value));
        }

        /** Convenience: an integer attr (e.g. the authoritative {@code turnNumber}). */
        public @NonNull Builder attr(@NonNull String key, int value) {
            return attr(key, JsonNodeFactory.instance.numberNode(value));
        }

        /** Convenience: a long attr. */
        public @NonNull Builder attr(@NonNull String key, long value) {
            return attr(key, JsonNodeFactory.instance.numberNode(value));
        }

        /** Convenience: a boolean attr (e.g. {@code success}). */
        public @NonNull Builder attr(@NonNull String key, boolean value) {
            return attr(key, BooleanNode.valueOf(value));
        }

        public @NonNull DeltaFrame build() {
            if (sessionId == null) {
                throw new IllegalStateException("DeltaFrame sessionId is required");
            }
            Kind frameKind = kind;
            if (frameKind == null) {
                throw new IllegalStateException("DeltaFrame kind is required");
            }
            return new DeltaFrame(sessionId, sequence, Instant.now(), frameKind, text, attrs);
        }
    }
}
