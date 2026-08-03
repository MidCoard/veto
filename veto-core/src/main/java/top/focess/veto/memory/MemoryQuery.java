package top.focess.veto.memory;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A query against the {@link MemoryStore}. Carries the query text (to be embedded at search time),
 * scope filters (tier / session / project), top-K, and a similarity score floor (results below the
 * floor are not returned — "better no memory than a wrong one", long_term_memory_tiers.md §5).
 */
public record MemoryQuery(
        @NonNull String queryText,
        @NonNull List<MemoryTier> tiers,
        @Nullable UUID sessionFilter,
        @Nullable UUID projectFilter,
        @NonNull UUID userId,
        int topK,
        float scoreFloor) {

    public MemoryQuery {
        tiers = List.copyOf(tiers);
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0");
        }
        if (scoreFloor < 0f || scoreFloor > 1f) {
            throw new IllegalArgumentException("scoreFloor must be in [0, 1]");
        }
    }

    /** A session-scoped search across the given tiers. */
    public static @NonNull MemoryQuery session(
            @NonNull String queryText, @NonNull UUID userId, @NonNull UUID sessionId) {
        return new MemoryQuery(
                queryText, List.of(MemoryTier.SESSION), sessionId, null, userId, 5, 0.5f);
    }

    /** A cross-session insights search. */
    public static @NonNull MemoryQuery crossSession(
            @NonNull String queryText, @NonNull UUID userId) {
        return new MemoryQuery(
                queryText, List.of(MemoryTier.CROSS_SESSION), null, null, userId, 5, 0.5f);
    }
}
