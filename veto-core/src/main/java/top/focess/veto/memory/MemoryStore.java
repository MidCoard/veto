package top.focess.veto.memory;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.TurnRecord;

/**
 * The memory storage and query interface (long_term_memory_tiers.md §7.1, Axis A). All memory
 * backends — pgvector, graph/entity, KV/relational, file — implement this same surface. The
 * agent-facing tools (recall_session, recall_insights, write_insight, forget) are thin wrappers
 * over this interface.
 *
 * <p>Implementations must enforce tenant isolation: a query for user U must only see memories owned
 * by U (per database_concurrency_isolation.md §2; RLS + application-side filters).
 */
public interface MemoryStore {

    /**
     * Forgiving retrieval (long_term_memory_tiers.md §5). Returns up to {@code query.topK()}
     * memories whose embedding cosine-similarity to the query embedding is at or above the
     * configured score floor, ranked descending. Results carry their source attribution.
     */
    @NonNull List<ScoredMemory> search(@NonNull MemoryQuery query);

    /** Append a new memory to the store (insert). */
    @NonNull MemoryId add(@NonNull Memory memory);

    /**
     * Capture one turn's content into Session LTM (long_term_memory_tiers.md §3.1 capture point).
     * The engine calls this at the capture points (after tool execution, after a thought, after a
     * user prompt). The content is already masked at the capture point.
     */
    void capture(@NonNull TurnRecord turn, @NonNull UUID sessionId, @NonNull UUID userId);

    /** Promote a Session-LTM memory to Cross-Session LTM (the curating boundary, §3.3). */
    void promote(@NonNull MemoryId id);

    /** Explicitly drop a memory (user- or agent-initiated). */
    void forget(@NonNull MemoryId id);

    /**
     * A search result: the memory + its similarity score (1.0 = identical embedding, 0.0 =
     * orthogonal).
     */
    record ScoredMemory(@NonNull Memory memory, float score) {}
}
