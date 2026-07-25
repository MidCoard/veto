package top.focess.veto.memory.embedder;

/**
 * Text -> vector embedding for the memory subsystem, decoupled from {@link
 * top.focess.veto.memory.MemoryStore} so the embedding model can evolve (local stub vs. provider
 * API) without touching storage.
 *
 * <p>The active bean is selected by {@link EmbedderConfiguration}: a {@link HashEmbedder} is the
 * local default ({@code @ConditionalOnMissingBean}); when {@code veto.memory.embedder.provider} is
 * configured a {@link ProviderEmbedder} overrides it. Stores and tools inject this single bean and
 * never call a provider directly.
 */
public interface Embedder {

    /**
     * Embed a chunk of text into a fixed-length float vector (L2-normalized).
     *
     * @param text the text to embed
     * @return the embedding vector; never {@code null}
     */
    float[] embed(String text);

    /**
     * The dimensionality of vectors this embedder produces. Must be stable across calls so that
     * {@link top.focess.veto.memory.PgvectorMemoryStore} can size its {@code vector(N)} column and
     * that indices/scores comparing two vectors stay well-formed.
     *
     * @return the vector dimension
     */
    int dimension();
}
