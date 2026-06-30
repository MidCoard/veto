package top.focess.veto.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.TurnRecord;

/**
 * JPA persistence for a raw {@link TurnRecord} — the durable per-turn audit/replay log (distinct
 * from the semantic {@link MemoryEntity} LTM entries, which are content+embedding for recall). One
 * row per captured turn, keyed by tenant ({@code user_id}) + session.
 *
 * <p>The {@code payload} {@link Map} is serialized to a TEXT column as JSON (portable — H2 has no
 * {@code jsonb} type; matching the {@link MemoryEntity} TEXT-column convention). The id is a fresh
 * UUID per row (the {@link TurnRecord} itself has no id).
 */
@Entity
@Table(name = "turn_records")
public class TurnRecordEntity {

    @Id private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "turn_number", nullable = false)
    private int turnNumber;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    protected TurnRecordEntity() {}

    /** Build a row from a captured {@link TurnRecord} + its tenant/session keys. */
    public static TurnRecordEntity of(
            TurnRecord turn, UUID sessionId, UUID userId, ObjectMapper mapper) {
        TurnRecordEntity e = new TurnRecordEntity();
        e.id = UUID.randomUUID().toString();
        e.userId = userId.toString();
        e.sessionId = sessionId == null ? null : sessionId.toString();
        e.turnNumber = turn.turnNumber();
        e.type = turn.type().name();
        e.payload = serializePayload(turn.payload(), mapper);
        e.timestamp = turn.timestamp();
        return e;
    }

    private static String serializePayload(Map<String, Object> payload, ObjectMapper mapper) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception ex) {
            // Best-effort: a payload that fails to serialize should not break capture.
            return payload.toString();
        }
    }

    public @NonNull String getId() {
        return id;
    }

    public @NonNull String getUserId() {
        return userId;
    }

    public @NonNull String getSessionId() {
        return sessionId;
    }

    public int getTurnNumber() {
        return turnNumber;
    }

    public @NonNull String getType() {
        return type;
    }

    public @NonNull String getPayload() {
        return payload;
    }

    public @NonNull Instant getTimestamp() {
        return timestamp;
    }
}
