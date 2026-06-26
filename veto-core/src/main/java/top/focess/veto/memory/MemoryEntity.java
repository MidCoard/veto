package top.focess.veto.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

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

    @Id private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "tier", nullable = false)
    private String tier;

    @Column(name = "project_id")
    private String projectId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "embedding", columnDefinition = "TEXT")
    private String embedding; // float[] serialized as comma-separated values for portability

    @Column(name = "source_ref", columnDefinition = "TEXT")
    private String sourceRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MemoryEntity() {}

    public MemoryEntity(Memory memory) {
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

    public static Memory toMemory(MemoryEntity e) {
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

    private static String serializeEmbedding(float[] e) {
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

    private static float[] deserializeEmbedding(String s) {
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

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getTier() {
        return tier;
    }

    public String getContent() {
        return content;
    }

    public String getEmbedding() {
        return embedding;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
