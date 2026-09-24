package top.focess.veto.builtin.memory;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.builtin.memory.embedder.Embedder;

/**
 * A pgvector-backed {@link MemoryStore}. It stores memories in PostgreSQL with a {@code vector(N)}
 * embedding column and performs approximate-nearest-neighbor search in SQL via pgvector's
 * cosine-distance operator {@code <=>} backed by an HNSW index, rather than the in-Java cosine loop
 * used by in-memory/JPA backends.
 *
 * <p>Activated by setting {@code veto.memory.store=pgvector}. <b>Requires PostgreSQL with the
 * pgvector extension installed</b> (the {@code vector} type and {@code <=>} operator are not part
 * of core Postgres and are absent from the H2 test database). On startup it self-provisions the
 * extension + the {@code pgvector_memories} table (guarded — if pgvector is unavailable it logs and
 * the store surfaces errors on use rather than failing the context load). It is therefore <b>not
 * exercised by the H2 test suite</b>, consistent with {@link JpaMemoryStore} (also untested); it is
 * verified against a real Postgres+pgvector in deployment.
 *
 * <p>Embedding is delegated to the injected {@link Embedder} (the local hash implementation by
 * default, a provider embedder when configured); the {@code vector(N)} column dimension tracks
 * {@link Embedder#dimension()}.
 */
public class PgvectorMemoryStore implements MemoryStore {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.builtin.memory.PgvectorMemoryStore");
    private static final String TABLE = "pgvector_memories";

    private final @NonNull EntityManager em;
    private final @NonNull Embedder embedder;
    private volatile boolean provisioned = false;

    public PgvectorMemoryStore(@NonNull EntityManager em, @NonNull Embedder embedder) {
        this.em = em;
        this.embedder = embedder;
    }

    /** Self-provision the extension + table (guarded; never fails the context load). */
    public void provision() {
        try {
            em.createNativeQuery("CREATE EXTENSION IF NOT EXISTS vector").executeUpdate();
            em.createNativeQuery(
                            "CREATE TABLE IF NOT EXISTS "
                                    + TABLE
                                    + " ("
                                    + "  id VARCHAR PRIMARY KEY,"
                                    + "  user_id VARCHAR NOT NULL,"
                                    + "  session_id VARCHAR,"
                                    + "  tier VARCHAR NOT NULL,"
                                    + "  project_id VARCHAR,"
                                    + "  content TEXT NOT NULL,"
                                    + "  embedding vector("
                                    + embedder.dimension()
                                    + ") NOT NULL,"
                                    + "  source_ref TEXT,"
                                    + "  created_at TIMESTAMP NOT NULL)")
                    .executeUpdate();
            // HNSW index for sub-linear cosine ANN. build it async-safe (IF NOT EXISTS).
            em.createNativeQuery(
                            "CREATE INDEX IF NOT EXISTS pgvector_memories_embedding_idx "
                                    + "ON "
                                    + TABLE
                                    + " USING hnsw (embedding vector_cosine_ops)")
                    .executeUpdate();
            provisioned = true;
            log.info("PgvectorMemoryStore: provisioned table {}", TABLE);
        } catch (PersistenceException e) {
            // pgvector not installed / not Postgres — the store is opted-in via config, so the
            // operator who set veto.memory.store=pgvector is expected to have pgvector. Surface a
            // clear log; queries will throw (fail-closed) rather than silently returning nothing.
            log.error(
                    "PgvectorMemoryStore: provisioning failed (pgvector extension/Postgres required) — "
                            + "searches will error: {}",
                    e.getClass().getSimpleName());
        }
    }

    @Override
    public @NonNull List<ScoredMemory> search(@NonNull MemoryQuery query) {
        String q = vecToString(embedder.embed(query.queryText()));
        // Over-fetch to absorb the post-filters (session/project) before trimming to topK.
        int limit = query.topK();
        var nativeQuery =
                em.createNativeQuery(
                                "SELECT id, user_id, session_id, tier, project_id, content,"
                                        + " source_ref, created_at, embedding::text,"
                                        + " (1 - (embedding <=> (:q)::vector)) AS score"
                                        + " FROM "
                                        + TABLE
                                        + " WHERE user_id = :uid AND tier IN (:tiers)"
                                        + " AND (1 - (embedding <=> (:q)::vector)) >= :floor"
                                        + (query.sessionFilter() == null
                                                ? ""
                                                : " AND session_id = :session")
                                        + (query.projectFilter() == null
                                                ? ""
                                                : " AND project_id = :project")
                                        + " ORDER BY embedding <=> (:q)::vector"
                                        + " LIMIT :limit")
                        .setParameter("q", q)
                        .setParameter("uid", query.userId().toString())
                        .setParameter("tiers", query.tiers().stream().map(Enum::name).toList())
                        .setParameter("floor", query.scoreFloor())
                        .setParameter("limit", limit);
        if (query.sessionFilter() != null)
            nativeQuery.setParameter("session", query.sessionFilter().toString());
        if (query.projectFilter() != null)
            nativeQuery.setParameter("project", query.projectFilter().toString());
        List<?> rows = nativeQuery.getResultList();
        List<ScoredMemory> matches = new ArrayList<>();
        for (Object result : rows) {
            if (!(result instanceof Object[] row)) {
                throw new IllegalStateException(
                        "Native memory search returned an unexpected row type");
            }
            Memory m = rowToMemory(row);
            // Tenant/tier already filtered in SQL; apply session/project in Java.
            var sessionFilter = query.sessionFilter();
            if (sessionFilter != null && !sessionFilter.equals(m.sessionId())) {
                continue;
            }
            var projectFilter = query.projectFilter();
            if (projectFilter != null && !projectFilter.equals(m.projectId())) {
                continue;
            }
            float score = ((Number) row[9]).floatValue();
            matches.add(new ScoredMemory(m, score));
        }
        matches.sort(Comparator.comparingDouble(ScoredMemory::score).reversed());
        if (matches.size() > query.topK()) {
            return matches.subList(0, query.topK());
        }
        return matches;
    }

    @Override
    @SuppressWarnings(
            "argument") // JPA setParameter accepts SQL null despite its external nullness model.
    public @NonNull MemoryId add(@NonNull Memory memory) {
        UUID sessionId = memory.sessionId();
        UUID projectId = memory.projectId();
        Memory.SourceRef sourceRef = memory.sourceRef();
        em.createNativeQuery(
                        "INSERT INTO "
                                + TABLE
                                + " (id, user_id, session_id, tier, project_id, content,"
                                + " embedding, source_ref, created_at)"
                                + " VALUES (:id, :uid, :sid, :tier, :pid, :content,"
                                + " (:vec)::vector, :sref, :cat)")
                .setParameter("id", memory.id().value().toString())
                .setParameter("uid", memory.userId().toString())
                .setParameter("sid", sessionId == null ? null : sessionId.toString())
                .setParameter("tier", memory.tier().name())
                .setParameter("pid", projectId == null ? null : projectId.toString())
                .setParameter("content", memory.content())
                .setParameter("vec", vecToString(memory.embedding()))
                .setParameter("sref", sourceRef == null ? null : sourceRef.kind())
                .setParameter("cat", Timestamp.from(memory.createdAt()))
                .executeUpdate();
        return memory.id();
    }

    @Override
    public MemoryId promote(@NonNull MemoryId id, @NonNull UUID userId) {
        // Session → Cross-Session: strip sessionId, bump tier. (Re-inserts with a fresh id to
        // preserve the curating boundary, like the other backends.)
        Memory m = findById(id, userId);
        if (m == null || !m.userId().equals(userId) || m.tier() != MemoryTier.SESSION) {
            return null;
        }
        if (!forget(id, userId)) {
            return null;
        }
        Memory promoted =
                new Memory(
                        MemoryId.random(),
                        m.userId(),
                        null,
                        MemoryTier.CROSS_SESSION,
                        m.projectId(),
                        m.content(),
                        m.embedding(),
                        m.sourceRef(),
                        Instant.now());
        add(promoted);
        return promoted.id();
    }

    @Override
    public boolean forget(@NonNull MemoryId id, @NonNull UUID userId) {
        int deleted =
                em.createNativeQuery(
                                "DELETE FROM " + TABLE + " WHERE id = :id AND user_id = :userId")
                        .setParameter("id", id.value().toString())
                        .setParameter("userId", userId.toString())
                        .executeUpdate();
        return deleted > 0;
    }

    @Override
    public void deleteOwner(@NonNull UUID userId) {
        em.createNativeQuery("DELETE FROM " + TABLE + " WHERE user_id = :user")
                .setParameter("user", userId.toString())
                .executeUpdate();
    }

    @Override
    public void deleteSession(@NonNull UUID userId, @NonNull UUID sessionId) {
        em.createNativeQuery(
                        "DELETE FROM " + TABLE + " WHERE user_id = :user AND session_id = :session")
                .setParameter("user", userId.toString())
                .setParameter("session", sessionId.toString())
                .executeUpdate();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Memory findById(@NonNull MemoryId id, @NonNull UUID userId) {
        List<?> rows =
                em.createNativeQuery(
                                "SELECT id, user_id, session_id, tier, project_id, content,"
                                        + " source_ref, created_at, embedding::text FROM "
                                        + TABLE
                                        + " WHERE id = :id AND user_id = :userId FOR UPDATE")
                        .setParameter("id", id.value().toString())
                        .setParameter("userId", userId.toString())
                        .getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        if (!(rows.get(0) instanceof Object[] row)) {
            throw new IllegalStateException("Native memory query returned an unexpected row type");
        }
        return rowToMemory(row);
    }

    @SuppressWarnings("ConstantValue")
    private static @NonNull Memory rowToMemory(Object @NonNull [] row) {
        UUID sessionId = row[2] == null ? null : UUID.fromString((String) row[2]);
        UUID projectId = row[4] == null ? null : UUID.fromString((String) row[4]);
        Timestamp ts = (Timestamp) row[7];
        MemoryTier tier = MemoryTier.valueOf((String) row[3]);
        if (tier == null) {
            throw new IllegalStateException("Native memory row contains an unknown tier");
        }
        return new Memory(
                new MemoryId(UUID.fromString((String) row[0])),
                UUID.fromString((String) row[1]),
                sessionId,
                tier,
                projectId,
                (String) row[5],
                parseVector((String) row[8]),
                new Memory.SourceRef((String) row[6], Map.of()),
                ts == null ? Instant.now() : ts.toInstant());
    }

    private static float @NonNull [] parseVector(@NonNull String value) {
        String body = value.substring(1, value.length() - 1);
        if (body.isBlank()) return new float[0];
        String[] parts = body.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) vector[i] = Float.parseFloat(parts[i]);
        return vector;
    }

    /**
     * Render a vector as the pgvector literal form {@code "[v0,v1,...]"} for the {@code ::vector}
     * cast.
     */
    private static @NonNull String vecToString(float @NonNull [] vec) {
        if (vec.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }
}
