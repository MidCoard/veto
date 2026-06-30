package top.focess.veto.memory;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.TurnRecord;

/**
 * The pgvector-backed {@link MemoryStore} — the production LTM backend (long_term_memory_tiers.md
 * §4). Stores memories in PostgreSQL with a {@code vector(N)} embedding column and performs
 * approximate-nearest-neighbor search in SQL via pgvector's cosine-distance operator {@code <=>}
 * (backed by an HNSW index), rather than the in-Java cosine loop the in-memory/JPA backends use.
 *
 * <p>Activated by setting {@code veto.memory.store=pgvector}. <b>Requires PostgreSQL with the
 * pgvector extension installed</b> (the {@code vector} type and {@code <=>} operator are not part
 * of core Postgres and are absent from the H2 test database). On startup it self-provisions the
 * extension + the {@code pgvector_memories} table (guarded — if pgvector is unavailable it logs and
 * the store surfaces errors on use rather than failing the context load). It is therefore <b>not
 * exercised by the H2 test suite</b>, consistent with {@link JpaMemoryStore} (also untested); it is
 * verified against a real Postgres+pgvector in deployment.
 *
 * <p>The {@link #embed(String)} stub is the same 64-dim deterministic hash the other backends use;
 * production plugs into the local embedding model (Part 14.4) and the {@code vector(64)} column
 * dimension tracks that.
 */
@Component
@ConditionalOnProperty(name = "veto.memory.store", havingValue = "pgvector")
public class PgvectorMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(PgvectorMemoryStore.class);
    private static final int EMBEDDING_DIM = 64;
    private static final String TABLE = "pgvector_memories";

    private final @NonNull EntityManager em;
    private volatile boolean provisioned = false;

    public
    @NonNull
    PgvectorMemoryStore(@NonNull EntityManager em) {
        this.em = em;
    }

    /** Self-provision the extension + table (guarded; never fails the context load). */
    @PostConstruct
    void provision() {
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
                                    + EMBEDDING_DIM
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
                    e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public @NonNull List<ScoredMemory> search(@NonNull MemoryQuery query) {
        String q = vecToString(embed(query.queryText()));
        // Over-fetch to absorb the post-filters (session/project) before trimming to topK.
        int limit = Math.max(query.topK() * 4, query.topK() + 8);
        List<Object[]> rows =
                em.createNativeQuery(
                                "SELECT id, user_id, session_id, tier, project_id, content,"
                                        + " source_ref, created_at,"
                                        + " (1 - (embedding <=> (:q)::vector)) AS score"
                                        + " FROM "
                                        + TABLE
                                        + " WHERE user_id = :uid AND tier IN (:tiers)"
                                        + " AND (1 - (embedding <=> (:q)::vector)) >= :floor"
                                        + " ORDER BY embedding <=> (:q)::vector"
                                        + " LIMIT :limit")
                        .setParameter("q", q)
                        .setParameter("uid", query.userId().toString())
                        .setParameter("tiers", query.tiers().stream().map(Enum::name).toList())
                        .setParameter("floor", query.scoreFloor())
                        .setParameter("limit", limit)
                        .getResultList();
        List<ScoredMemory> matches = new ArrayList<>();
        for (Object[] row : rows) {
            Memory m = rowToMemory(row);
            // Tenant/tier already filtered in SQL; apply session/project in Java.
            if (query.sessionFilter() != null && !query.sessionFilter().equals(m.sessionId())) {
                continue;
            }
            if (query.projectFilter() != null && !query.projectFilter().equals(m.projectId())) {
                continue;
            }
            float score = ((Number) row[8]).floatValue();
            matches.add(new ScoredMemory(m, score));
        }
        matches.sort(Comparator.comparingDouble(ScoredMemory::score).reversed());
        if (matches.size() > query.topK()) {
            return matches.subList(0, query.topK());
        }
        return matches;
    }

    @Override
    public @NonNull MemoryId add(@NonNull Memory memory) {
        em.createNativeQuery(
                        "INSERT INTO "
                                + TABLE
                                + " (id, user_id, session_id, tier, project_id, content,"
                                + " embedding, source_ref, created_at)"
                                + " VALUES (:id, :uid, :sid, :tier, :pid, :content,"
                                + " (:vec)::vector, :sref, :cat)")
                .setParameter("id", memory.id().value().toString())
                .setParameter("uid", memory.userId().toString())
                .setParameter(
                        "sid", memory.sessionId() == null ? null : memory.sessionId().toString())
                .setParameter("tier", memory.tier().name())
                .setParameter(
                        "pid", memory.projectId() == null ? null : memory.projectId().toString())
                .setParameter("content", memory.content())
                .setParameter("vec", vecToString(memory.embedding()))
                .setParameter("sref", memory.sourceRef().kind())
                .setParameter("cat", java.sql.Timestamp.from(memory.createdAt()))
                .executeUpdate();
        return memory.id();
    }

    @Override
    public void capture(@NonNull TurnRecord turn, @NonNull UUID sessionId, @NonNull UUID userId) {
        if (turn == null || sessionId == null || userId == null) {
            return;
        }
        String content = captureText(turn);
        if (content == null || content.isBlank()) {
            return;
        }
        Memory m =
                new Memory(
                        MemoryId.random(),
                        userId,
                        sessionId,
                        MemoryTier.SESSION,
                        null,
                        content,
                        embed(content),
                        Memory.SourceRef.turnRange(turn.turnNumber(), turn.turnNumber()),
                        Instant.now());
        add(m);
    }

    @Override
    public void promote(@NonNull MemoryId id) {
        // Session → Cross-Session: strip sessionId, bump tier. (Re-inserts with a fresh id to
        // preserve the curating boundary, like the other backends.)
        Memory m = findById(id);
        if (m == null || m.tier() != MemoryTier.SESSION) {
            return;
        }
        forget(id);
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
    }

    @Override
    public void forget(@NonNull MemoryId id) {
        em.createNativeQuery("DELETE FROM " + TABLE + " WHERE id = :id")
                .setParameter("id", id.value().toString())
                .executeUpdate();
    }

    @Override
    public float[] embed(@NonNull String text) {
        byte[] bytes = text.getBytes();
        float[] vec = new float[EMBEDDING_DIM];
        for (int i = 0; i < bytes.length; i++) {
            vec[i % EMBEDDING_DIM] += (bytes[i] & 0xff) / 255f;
        }
        float norm = 0f;
        for (float v : vec) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0f) {
            for (int i = 0; i < vec.length; i++) {
                vec[i] /= norm;
            }
        }
        return vec;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Memory findById(MemoryId id) {
        List<Object[]> rows =
                em.createNativeQuery(
                                "SELECT id, user_id, session_id, tier, project_id, content,"
                                        + " source_ref, created_at FROM "
                                        + TABLE
                                        + " WHERE id = :id")
                        .setParameter("id", id.value().toString())
                        .getResultList();
        return rows.isEmpty() ? null : rowToMemory(rows.get(0));
    }

    private static Memory rowToMemory(Object[] row) {
        UUID sessionId = row[2] == null ? null : UUID.fromString((String) row[2]);
        UUID projectId = row[4] == null ? null : UUID.fromString((String) row[4]);
        java.sql.Timestamp ts = (java.sql.Timestamp) row[7];
        return new Memory(
                new MemoryId(UUID.fromString((String) row[0])),
                UUID.fromString((String) row[1]),
                sessionId,
                MemoryTier.valueOf((String) row[3]),
                projectId,
                (String) row[5],
                new float[0], // embedding not read back for result delivery
                new Memory.SourceRef((String) row[6], Map.of()),
                ts == null ? Instant.now() : ts.toInstant());
    }

    /**
     * Render a vector as the pgvector literal form {@code "[v0,v1,...]"} for the {@code ::vector}
     * cast.
     */
    private static String vecToString(float[] vec) {
        if (vec == null || vec.length == 0) {
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

    private static String captureText(TurnRecord turn) {
        return switch (turn.type()) {
            case ASSISTANT_THOUGHT -> {
                Object thought = turn.payload().get("response");
                yield thought == null ? null : thought.toString();
            }
            case ASSISTANT_RESPONSE, USER_PROMPT, USER_INTERRUPT -> {
                Object content = turn.payload().get("content");
                if (content == null) {
                    content = turn.payload().get("feedback");
                }
                yield content == null ? null : content.toString();
            }
            case TOOL_RESPONSE -> {
                Object content = turn.payload().get("content");
                yield content == null ? null : content.toString();
            }
            default -> null;
        };
    }
}
