package top.focess.veto.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Durable group snapshots, independent of rewound model context. */
@Entity
@Table(
        name = "group_history",
        indexes = @Index(name = "idx_group_history_session", columnList = "session_id,recorded_at"))
public class GroupHistoryEntity {
    @Id private @NonNull String id = "";

    @Column(name = "session_id", nullable = false)
    private @NonNull String sessionId = "";

    @Column(name = "recorded_at", nullable = false)
    private @NonNull Instant recordedAt = Instant.EPOCH;

    @Column(columnDefinition = "TEXT", nullable = false)
    private @NonNull String payload = "";

    protected GroupHistoryEntity() {}

    public GroupHistoryEntity(@NonNull String sessionId, @NonNull String payload) {
        this.id = UUID.randomUUID().toString();
        this.sessionId = sessionId;
        this.recordedAt = Instant.now();
        this.payload = payload;
    }

    public @NonNull Instant getRecordedAt() {
        return recordedAt;
    }

    public @NonNull String getPayload() {
        return payload;
    }
}
