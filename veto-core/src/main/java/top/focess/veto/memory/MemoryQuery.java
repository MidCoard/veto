package top.focess.veto.memory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A query against the {@link MemoryStore}. Carries the query text (to be embedded at search time),
 * scope filters (tier / session / project), top-K, and a similarity score floor (results below the
 * floor are not returned — "better no memory than a wrong one", long_term_memory_tiers.md §5).
 */
public record MemoryQuery(
        String queryText,
        List<MemoryTier> tiers,
        UUID sessionFilter,
        UUID projectFilter,
        UUID userId,
        int topK,
        float scoreFloor) {

    public MemoryQuery {
        Objects.requireNonNull(queryText, "queryText");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tiers, "tiers");
        tiers = List.copyOf(tiers);
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0");
        }
        if (scoreFloor < 0f || scoreFloor > 1f) {
            throw new IllegalArgumentException("scoreFloor must be in [0, 1]");
        }
    }

    /** A session-scoped search across the given tiers. */
    public static MemoryQuery session(String queryText, UUID userId, UUID sessionId) {
        return new MemoryQuery(
                queryText, List.of(MemoryTier.SESSION), sessionId, null, userId, 5, 0.5f);
    }

    /** A cross-session insights search. */
    public static MemoryQuery crossSession(String queryText, UUID userId) {
        return new MemoryQuery(
                queryText, List.of(MemoryTier.CROSS_SESSION), null, null, userId, 5, 0.5f);
    }
}
