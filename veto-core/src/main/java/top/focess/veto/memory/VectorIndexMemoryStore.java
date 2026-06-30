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

/**
 * A {@link MemoryStore} backed by the in-memory {@link VectorIndex} (a brute-force cosine
 * similarity index, the MVP reference for the pgvector production path). Stores memories in a
 * thread-safe map + vector index; supports the two-axis abstraction (Axis A storage/query is this
 * vector index; Axis B triggering is whatever calls search).
 *
 * <p>Activated by setting {@code veto.memory.store=vector}. Falls back to {@link
 * InMemoryMemoryStore} (default) or {@link JpaMemoryStore} otherwise.
 */
@Component
@ConditionalOnProperty(name = "veto.memory.store", havingValue = "vector")
public class VectorIndexMemoryStore implements MemoryStore {

    private final @NonNull VectorIndex index;

    /** Memories are stored separately so we can attach metadata for the search result. */
    private final java.util.concurrent.ConcurrentMap<UUID, Memory> store =
            new java.util.concurrent.ConcurrentHashMap<>();

    public
    @NonNull
    VectorIndexMemoryStore(@NonNull VectorIndex index) {
        this.index = index;
    }

    @Override
    public @NonNull List<ScoredMemory> search(@NonNull MemoryQuery query) {
        // Over-fetch from the index in a single widening loop. The 4x budget covers the common
        // case where the user/tier/session/project filters are loose (4x hit-rate is generous);
        // if the post-filter result is still short of `topK`, widen the budget and re-query
        // (capped at QUERY_WIDENING_CAP× to bound the worst-case cost). Without the re-query
        // a sparse user in a multi-tenant corpus could silently receive zero results even
        // when highly relevant memories exist outside the original window.
        final float[] queryVec = embed(query.queryText());
        final int initialBudget = query.topK() * 4;
        final int cap = query.topK() * QUERY_WIDENING_CAP;
        int budget = initialBudget;
        List<ScoredMemory> results = List.of();
        while (true) {
            List<VectorIndex.Match> matches = index.topK(queryVec, budget);
            results = new ArrayList<>();
            for (VectorIndex.Match match : matches) {
                Memory m = store.get(match.id());
                if (m == null) {
                    continue;
                }
                if (!m.userId().equals(query.userId())) {
                    continue;
                }
                if (!query.tiers().contains(m.tier())) {
                    continue;
                }
                if (query.sessionFilter() != null && !query.sessionFilter().equals(m.sessionId())) {
                    continue;
                }
                if (query.projectFilter() != null && !query.projectFilter().equals(m.projectId())) {
                    continue;
                }
                if (match.score() >= query.scoreFloor()) {
                    results.add(new ScoredMemory(m, match.score()));
                }
            }
            results.sort(Comparator.comparingDouble(ScoredMemory::score).reversed());
            if (results.size() >= query.topK() || budget >= cap) {
                break;
            }
            // Widen the budget and try again. Cap grows geometrically to bound the worst case.
            budget = Math.min(cap, budget * 4);
        }
        if (results.size() > query.topK()) {
            return results.subList(0, query.topK());
        }
        return results;
    }

    /**
     * Maximum multiple of {@code topK} the re-query loop will fetch. Default 64× topK — well above
     * the 4× initial budget so most sparse-user queries can recover, but still bounded so a
     * malicious or extreme query can't burn the whole index.
     */
    private static final int QUERY_WIDENING_CAP = 64;

    @Override
    public @NonNull MemoryId add(@NonNull Memory memory) {
        store.put(idOf(memory.id()), memory);
        index.insert(idOf(memory.id()), memory.embedding());
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
        float[] embedding = embed(content);
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
        Memory m = store.get(idOf(id));
        if (m == null || m.tier() != MemoryTier.SESSION) {
            return;
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
        store.remove(idOf(id));
        index.remove(idOf(id));
        store.put(idOf(promoted.id()), promoted);
        index.insert(idOf(promoted.id()), promoted.embedding());
    }

    @Override
    public void forget(@NonNull MemoryId id) {
        // store is keyed by UUID (see add() above) — apply idOf() consistently so the
        // entry is actually evicted. Previously the raw MemoryId was passed, making
        // store.remove a no-op and leaving the memory visible to future searches.
        UUID key = idOf(id);
        store.remove(key);
        index.remove(key);
    }

    @Override
    public float[] embed(@NonNull String text) {
        // Same deterministic stub. Production would call a local embedding model (Part 14.4).
        byte[] bytes = text.getBytes();
        float[] vec = new float[64];
        for (int i = 0; i < bytes.length; i++) {
            vec[i % 64] += (bytes[i] & 0xff) / 255f;
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

    /** Test-only inspection of the store contents. */
    public java.util.Map<UUID, Memory> snapshot() {
        return java.util.Map.copyOf(store);
    }

    private static UUID idOf(MemoryId id) {
        return id.value();
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
