package top.focess.veto.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.memory.embedder.Embedder;

/**
 * The JPA-backed {@link MemoryStore} — the production path. Persists memories in PostgreSQL via
 * Spring Data JPA + Hibernate (the {@code jsonb} column type for the payload; float-array embedding
 * as comma-separated values for portability; the production path would use pgvector's {@code
 * vector} type).
 *
 * <p>Activated by setting {@code veto.memory.store=jpa} (the default is {@code memory}, the
 * in-process {@link InMemoryMemoryStore}). Falls back to in-memory if the repository is
 * unavailable.
 */
@Component
@ConditionalOnProperty(name = "veto.memory.store", havingValue = "jpa")
public class JpaMemoryStore implements MemoryStore {

    private final @NonNull MemoryRepository repository;
    private final @NonNull Embedder embedder;

    public JpaMemoryStore(@NonNull MemoryRepository repository, @NonNull Embedder embedder) {
        this.repository = repository;
        this.embedder = embedder;
    }

    @Override
    public @NonNull List<ScoredMemory> search(@NonNull MemoryQuery query) {
        // 1. Fetch candidate rows from the DB (tenant-scoped via userId filter).
        List<MemoryEntity> candidates =
                repository.findByUserIdAndTierIn(
                        query.userId().toString(), query.tiers().stream().map(Enum::name).toList());
        // 2. Compute cosine similarity in Java (production: pgvector).
        float[] queryVec = embedder.embed(query.queryText());
        List<ScoredMemory> matches = new ArrayList<>();
        for (MemoryEntity e : candidates) {
            Memory m = MemoryEntity.toMemory(e);
            // Session filter
            var sessionFilter = query.sessionFilter();
            if (sessionFilter != null && !sessionFilter.equals(m.sessionId())) {
                continue;
            }
            // Project filter
            var projectFilter = query.projectFilter();
            if (projectFilter != null && !projectFilter.equals(m.projectId())) {
                continue;
            }
            float score = cosineSimilarity(queryVec, m.embedding());
            if (score >= query.scoreFloor()) {
                matches.add(new ScoredMemory(m, score));
            }
        }
        matches.sort(Comparator.comparingDouble(ScoredMemory::score).reversed());
        if (matches.size() > query.topK()) {
            return matches.subList(0, query.topK());
        }
        return matches;
    }

    @Override
    public @NonNull MemoryId add(@NonNull Memory memory) {
        repository.save(new MemoryEntity(memory));
        return memory.id();
    }

    @Override
    public void capture(@NonNull TurnRecord turn, @NonNull UUID sessionId, @NonNull UUID userId) {
        String content = captureText(turn);
        if (content == null || content.isBlank()) {
            return;
        }
        float[] embedding = embedder.embed(content);
        Memory m =
                new Memory(
                        MemoryId.random(),
                        userId,
                        sessionId,
                        MemoryTier.SESSION,
                        null,
                        content,
                        embedding,
                        Memory.SourceRef.turnRange(turn.turnNumber(), turn.turnNumber()),
                        Instant.now());
        add(m);
    }

    @Override
    public void promote(@NonNull MemoryId id) {
        MemoryEntity e = repository.findById(id.value().toString()).orElse(null);
        if (e == null || !MemoryTier.SESSION.name().equals(e.getTier())) {
            return;
        }
        Memory m = MemoryEntity.toMemory(e);
        repository.delete(e);
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
        repository.save(new MemoryEntity(promoted));
    }

    @Override
    public void forget(@NonNull MemoryId id) {
        repository.deleteById(id.value().toString());
    }

    private static float cosineSimilarity(float @NonNull [] a, float @NonNull [] b) {
        if (a.length == 0 || b.length == 0) {
            return 0f;
        }
        int n = Math.min(a.length, b.length);
        float dot = 0f, na = 0f, nb = 0f;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0f || nb == 0f) {
            return 0f;
        }
        return dot / ((float) Math.sqrt(na) * (float) Math.sqrt(nb));
    }

    private static String captureText(@NonNull TurnRecord turn) {
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
