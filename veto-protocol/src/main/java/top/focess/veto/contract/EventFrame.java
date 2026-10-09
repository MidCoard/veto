package top.focess.veto.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * A single streaming update emitted by the backend and consumed by a transport such as a terminal,
 * REST client, or WebSocket session. Frames are ordered by {@code sequence} within a session. A
 * null session id denotes connection-scoped command output and cannot enter session routing.
 *
 * <p>Frame kinds:
 *
 * <ul>
 *   <li>{@link Kind#NOTICE} — deterministic backend feedback, independent of model output
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
 * <p>The shared protocol codec serializes the same frame for every transport. Backend brokers
 * assign sequence numbers and route frames; they do not define the wire representation.
 */
public record EventFrame(
        UUID sessionId,
        @JsonProperty(value = "sequence", required = true) @JsonSetter(nulls = Nulls.FAIL)
                long sequence,
        Instant emittedAt,
        @JsonProperty(value = "kind", required = true) @JsonSetter(nulls = Nulls.FAIL)
                @NonNull Kind kind,
        String text,
        Map<String, JsonNode> attrs)
        implements Frame.ServerFrame {

    /** Classification of the streaming update a frame carries. */
    public enum Kind {
        /** Connection-scoped command output, independent of any agent session. */
        COMMAND_MESSAGE,
        /** Transient server-authored presentation notice, excluded from conversation history. */
        NOTICE,
        ASSISTANT_THOUGHT,
        ASSISTANT_MESSAGE,
        TOOL_CALL,
        TOOL_RESULT,
        COMPACTION,
        TOKEN_USAGE,
        RECORD_UPDATED,
        SESSION_INVALIDATED,
        PLUGIN_EVENT,
        BREAKER_TRIPPED,
        ERROR,
        /** A HITL veto was raised and is waiting for the user's decision. */
        VETO_REQUIRED,
        /** A previously raised veto was resolved (approved/declined/edited). */
        VETO_RESOLVED,
        /** The episode finished; carries the final success flag so clients can stop waiting. */
        EPISODE_DONE
    }

    /** Normalizes a missing timestamp, text, or attribute map so the accessors stay non-null. */
    public EventFrame {
        Objects.requireNonNull(kind, "kind");
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
            throw new IllegalStateException("EventFrame timestamp was not normalized");
        }
        return value;
    }

    /** The canonical constructor normalizes missing text to an empty string. */
    @Override
    public @NonNull String text() {
        String value = text;
        if (value == null) {
            throw new IllegalStateException("EventFrame text was not normalized");
        }
        return value;
    }

    /** The canonical constructor normalizes missing attributes to an immutable empty map. */
    @Override
    public @NonNull Map<@NonNull String, @NonNull JsonNode> attrs() {
        Map<@NonNull String, @NonNull JsonNode> value = attrs;
        if (value == null) {
            throw new IllegalStateException("EventFrame attributes were not normalized");
        }
        return value;
    }

    /** Connection-scoped command output; session brokers must reject this frame. */
    public static @NonNull EventFrame command(@NonNull String text) {
        return builder().kind(Kind.COMMAND_MESSAGE).text(text).build();
    }

    /** Returns a new {@link Builder} for a frame. */
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

        /** Adds an arbitrary JSON attribute. */
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

        /**
         * Builds the frame, stamping it with the current time.
         *
         * @throws IllegalStateException if the session id or kind was never set
         */
        public @NonNull EventFrame build() {
            Kind frameKind = kind;
            if (frameKind == null) {
                throw new IllegalStateException("EventFrame kind is required");
            }
            return new EventFrame(sessionId, sequence, Instant.now(), frameKind, text, attrs);
        }
    }
}
