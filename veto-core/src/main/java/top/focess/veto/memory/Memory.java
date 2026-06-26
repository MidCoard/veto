package top.focess.veto.memory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
        MemoryId id,
        UUID userId,
        UUID sessionId,
        MemoryTier tier,
        UUID projectId,
        String content,
        float[] embedding,
        SourceRef sourceRef,
        Instant createdAt) {

    public Memory {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
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
    public record SourceRef(String kind, Map<String, Object> attrs) {
        public SourceRef {
            if (attrs == null) {
                attrs = Map.of();
            } else {
                attrs = Map.copyOf(attrs);
            }
        }

        public static SourceRef callId(String callId) {
            return new SourceRef("call_id", Map.of("call_id", callId));
        }

        public static SourceRef turnRange(int from, int to) {
            return new SourceRef("turn_range", Map.of("from", from, "to", to));
        }

        public static SourceRef insightOrigin(String origin) {
            return new SourceRef("insight_origin", Map.of("origin", origin));
        }
    }
}
