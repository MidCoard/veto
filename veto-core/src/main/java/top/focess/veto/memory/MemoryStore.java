package top.focess.veto.memory;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.TurnRecord;

/**
 * The memory storage and query interface. All memory backends — pgvector, graph/entity,
 * KV/relational, or file — implement this same surface. The agent-facing tools (recall_session,
 * recall_insights, write_insight, forget) are thin wrappers over this interface.
 *
 * <p>Implementations must enforce tenant isolation: a query for user U may only return memories
 * owned by U, using database row-level security and application-side filters as appropriate.
 */
public interface MemoryStore {

    /**
     * Forgiving retrieval. Returns up to {@code query.topK()} memories whose embedding
     * cosine-similarity to the query embedding is at or above the configured score floor, ranked
     * descending. Results carry their source attribution.
     */
    @NonNull List<ScoredMemory> search(@NonNull MemoryQuery query);

    /** Append a new memory to the store (insert). */
    @NonNull MemoryId add(@NonNull Memory memory);

    /**
     * Captures one turn's already-masked content into session memory. The engine calls this after
     * tool execution, after a thought, and after a user prompt.
     */
    void capture(@NonNull TurnRecord turn, @NonNull UUID sessionId, @NonNull UUID userId);

    /**
     * Promote an owned Session-LTM memory to Cross-Session LTM. The curating boundary replaces the
     * original memory with a new id. Returns the replacement id, or null when the source id is
     * absent, belongs to another user, or is not promotable.
     */
    MemoryId promote(@NonNull MemoryId id, @NonNull UUID userId);

    /** Drop an owned memory. Returns false rather than revealing another user's memory. */
    boolean forget(@NonNull MemoryId id, @NonNull UUID userId);

    /**
     * A search result: the memory + its similarity score (1.0 = identical embedding, 0.0 =
     * orthogonal).
     */
    record ScoredMemory(@NonNull Memory memory, float score) {}
}
