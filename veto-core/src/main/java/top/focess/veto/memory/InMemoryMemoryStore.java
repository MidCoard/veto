package top.focess.veto.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.memory.embedder.Embedder;

/**
 * A simple in-process {@link MemoryStore} for the MVP path. Stores all memories in a {@link
 * ConcurrentHashMap} keyed by id, with cosine-similarity search in Java. Not persistent; on JVM
 * restart all memories are lost.
 *
 * <p>The reference production backend is pgvector (long_term_memory_tiers.md §4); this in-memory
 * implementation gives us a working memory stack without the operational complexity of standing up
 * PostgreSQL + pgvector for tests and the local CLI path.
 *
 * <p>The embed(String) method is a deterministic stub — it hashes the text to produce a
 * fixed-length vector so similarity is meaningful (identical texts → 1.0; very different texts →
 * ~0). The production backend plugs into the local embedding model (Part 14.4).
 */
@Component
@ConditionalOnProperty(name = "veto.memory.store", havingValue = "memory", matchIfMissing = true)
@SuppressWarnings(
        "DuplicatedCode") // Store implementations intentionally share filtering and vector math.
public class InMemoryMemoryStore implements MemoryStore {

    private final @NonNull Embedder embedder;
    private final @NonNull ConcurrentMap<MemoryId, Memory> store = new ConcurrentHashMap<>();

    public InMemoryMemoryStore(@NonNull Embedder embedder) {
        this.embedder = embedder;
    }

    @Override
    public @NonNull List<ScoredMemory> search(@NonNull MemoryQuery query) {
        float[] queryVec = embedder.embed(query.queryText());
        List<ScoredMemory> matches = new ArrayList<>();
        for (Memory m : store.values()) {
            // Tenant isolation: only the requesting user can see their memories.
            if (!m.userId().equals(query.userId())) {
                continue;
            }
            // Tier filter.
            if (!query.tiers().contains(m.tier())) {
                continue;
            }
            // Session filter (session-scoped queries only see their session).
            var sessionFilter = query.sessionFilter();
            if (sessionFilter != null && !sessionFilter.equals(m.sessionId())) {
                continue;
            }
            // Project filter.
            var projectFilter = query.projectFilter();
            if (projectFilter != null && !projectFilter.equals(m.projectId())) {
                continue;
            }
            if (m.embedding().length == 0) {
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
        store.put(memory.id(), memory);
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
    public boolean promote(@NonNull MemoryId id, @NonNull UUID userId) {
        Memory m = store.get(id);
        if (m == null || !m.userId().equals(userId) || m.tier() != MemoryTier.SESSION) {
            return false;
        }
        Memory promoted =
                new Memory(
                        MemoryId.random(),
                        m.userId(),
                        null, // sessionId stripped at the curating boundary
                        MemoryTier.CROSS_SESSION,
                        m.projectId(),
                        m.content(),
                        m.embedding(),
                        m.sourceRef(),
                        Instant.now());
        store.remove(id);
        store.put(promoted.id(), promoted);
        return true;
    }

    @Override
    public boolean forget(@NonNull MemoryId id, @NonNull UUID userId) {
        Memory memory = store.get(id);
        return memory != null && memory.userId().equals(userId) && store.remove(id, memory);
    }

    private static float cosineSimilarity(float @NonNull [] a, float @NonNull [] b) {
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

    /** Test-only inspection of the store contents. */
    public @NonNull Map<MemoryId, Memory> snapshot() {
        return Map.copyOf(store);
    }

    /** Total memory count — for tests + diagnostics. */
    public int size() {
        return store.size();
    }

    /** Test-only: clear all memories. */
    public void clear() {
        store.clear();
    }

    /**
     * Prune Cross-Session LTM per the LLD's pruning policy (recency + size cap). Removes the oldest
     * Cross-Session LTM entries when the count exceeds {@code maxCrossSessionSize}.
     * Newest-by-createdAt is kept.
     */
    public int pruneCrossSessionLtm(int maxCrossSessionSize) {
        if (maxCrossSessionSize < 0) {
            return 0;
        }
        List<Memory> crossSession = new ArrayList<>();
        for (Memory m : store.values()) {
            if (m.tier() == MemoryTier.CROSS_SESSION) {
                crossSession.add(m);
            }
        }
        if (crossSession.size() <= maxCrossSessionSize) {
            return 0;
        }
        crossSession.sort(Comparator.comparing(Memory::createdAt).reversed());
        int toRemove = crossSession.size() - maxCrossSessionSize;
        int removed = 0;
        for (int i = maxCrossSessionSize; i < crossSession.size(); i++) {
            store.remove(crossSession.get(i).id());
            removed++;
        }
        return removed;
    }
}
