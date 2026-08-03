package top.focess.veto.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * JPA persistence for a {@link Memory}. Stored in PostgreSQL with JSONB columns (Hibernate's {@code
 * jsonb} type). The embedding is stored as a float array (the production path would use pgvector's
 * {@code vector} type — the float-array form is a portable fallback that the reference {@link
 * InMemoryMemoryStore} can also use for similarity search).
 *
 * <p>Unique key on id; secondary index on (user_id, session_id, tier) for the common query "list a
 * user's session LTM". The {@code @Filter} (or equivalent RLS predicate, per Part 4.6
 * database_concurrency_isolation.md) restricts reads to the current user.
 */
@Entity
@Table(name = "memories")
public class MemoryEntity {

    @Id @NonNull private String id;

    @Column(name = "user_id", nullable = false)
    @NonNull
    private String userId;

    @Column(name = "session_id")
    @Nullable
    private String sessionId;

    @Column(name = "tier", nullable = false)
    @NonNull
    private String tier;

    @Column(name = "project_id")
    @Nullable
    private String projectId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    @NonNull
    private String content;

    @Column(name = "embedding", columnDefinition = "TEXT")
    @Nullable
    private String embedding; // float[] serialized as comma-separated values for portability

    @Column(name = "source_ref", columnDefinition = "TEXT")
    @Nullable
    private String sourceRef;

    @Column(name = "created_at", nullable = false)
    @NonNull
    private Instant createdAt;

    protected MemoryEntity() {}

    public
    @NonNull
    MemoryEntity(@NonNull Memory memory) {
        this.id = memory.id().value().toString();
        this.userId = memory.userId().toString();
        this.sessionId = memory.sessionId() == null ? null : memory.sessionId().toString();
        this.tier = memory.tier().name();
        this.projectId = memory.projectId() == null ? null : memory.projectId().toString();
        this.content = memory.content();
        this.embedding = serializeEmbedding(memory.embedding());
        this.sourceRef = memory.sourceRef().kind() + " " + memory.sourceRef().attrs();
        this.createdAt = memory.createdAt();
    }

    public static @NonNull Memory toMemory(@NonNull MemoryEntity e) {
        return new Memory(
                new MemoryId(UUID.fromString(e.id)),
                UUID.fromString(e.userId),
                e.sessionId == null ? null : UUID.fromString(e.sessionId),
                MemoryTier.valueOf(e.tier),
                e.projectId == null ? null : UUID.fromString(e.projectId),
                e.content,
                deserializeEmbedding(e.embedding),
                new Memory.SourceRef(
                        "stored", java.util.Map.of("raw", e.sourceRef == null ? "" : e.sourceRef)),
                e.createdAt);
    }

    private static @NonNull String serializeEmbedding(float @Nullable [] e) {
        if (e == null || e.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < e.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(e[i]);
        }
        return sb.toString();
    }

    private static float @NonNull [] deserializeEmbedding(@Nullable String s) {
        if (s == null || s.isBlank()) {
            return new float[0];
        }
        String[] parts = s.split(",");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Float.parseFloat(parts[i]);
        }
        return out;
    }

    public @NonNull String getId() {
        return id;
    }

    public @NonNull String getUserId() {
        return userId;
    }

    public @Nullable String getSessionId() {
        return sessionId;
    }

    public @NonNull String getTier() {
        return tier;
    }

    public @NonNull String getContent() {
        return content;
    }

    public @Nullable String getEmbedding() {
        return embedding;
    }

    public @NonNull Instant getCreatedAt() {
        return createdAt;
    }
}
