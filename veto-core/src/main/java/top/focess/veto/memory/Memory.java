package top.focess.veto.memory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A single memory entry stored by the {@link MemoryStore}. Carries the captured content (already
 * masked via {@code accept_and_mask}), its embedding, source attribution, and tier tag.
 *
 * <p>Per long_term_memory_tiers.md §2: Session LTM entries have a non-null {@code sessionId};
 * Cross-Session LTM entries have a null {@code sessionId} (the curating boundary that strips it).
 * The {@code tier} is a runtime convenience (derivable from sessionId==null) but stored explicitly
 * for query speed.
 */
public record Memory(
        @NonNull MemoryId id,
        @NonNull UUID userId,
        @Nullable UUID sessionId,
        @NonNull MemoryTier tier,
        @Nullable UUID projectId,
        @NonNull String content,
        float @Nullable [] embedding,
        @Nullable SourceRef sourceRef,
        @NonNull Instant createdAt) {

    public Memory {
        if (tier == MemoryTier.SESSION && sessionId == null) {
            throw new IllegalArgumentException("SESSION-tier memory must have sessionId");
        }
        if (tier == MemoryTier.CROSS_SESSION && sessionId != null) {
            throw new IllegalArgumentException("CROSS_SESSION-tier memory must not have sessionId");
        }
        embedding = embedding == null ? new float[0] : embedding.clone();
    }

    /**
     * Reference to the source of this memory (e.g. the originating turn range or insight origin).
     */
    public record SourceRef(@NonNull String kind, @NonNull Map<String, Object> attrs) {
        public SourceRef {
            attrs = Map.copyOf(attrs);
        }

        public static @NonNull SourceRef callId(@NonNull String callId) {
            return new SourceRef("call_id", Map.of("call_id", callId));
        }

        public static @NonNull SourceRef turnRange(int from, int to) {
            return new SourceRef("turn_range", Map.of("from", from, "to", to));
        }

        public static @NonNull SourceRef insightOrigin(@NonNull String origin) {
            return new SourceRef("insight_origin", Map.of("origin", origin));
        }
    }
}
