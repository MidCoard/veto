package top.focess.veto.builtin.memory.embedder;

import org.jspecify.annotations.NonNull;
import top.focess.veto.builtin.memory.MemoryStore;
import top.focess.veto.builtin.memory.PgvectorMemoryStore;

/**
 * Text -> vector embedding for the memory subsystem, decoupled from {@link MemoryStore} so the
 * embedding model can evolve (local implementation vs. provider API) without touching storage.
 *
 * <p>The builtin runtime selects {@link HashEmbedder} as the local default or a host-authorized
 * TextEmbedding port for a configured provider. Stores and tools share that selected instance and
 * never call a provider directly.
 */
public interface Embedder {

    /**
     * Embed a chunk of text into a fixed-length float vector (L2-normalized).
     *
     * @param text the text to embed
     * @return the embedding vector; never {@code null}
     */
    float @NonNull [] embed(@NonNull String text);

    /**
     * The dimensionality of vectors this embedder produces. Must be stable across calls so that
     * {@link PgvectorMemoryStore} can size its {@code vector(N)} column and that indices/scores
     * comparing two vectors stay well-formed.
     *
     * @return the vector dimension
     */
    int dimension();
}
