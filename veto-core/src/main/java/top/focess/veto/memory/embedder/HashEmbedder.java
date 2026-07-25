package top.focess.veto.memory.embedder;

import org.jspecify.annotations.NonNull;

/**
 * Deterministic hash embedder - the local default
 * ({@code @ConditionalOnMissingBean(Embedder.class)} in {@link EmbedderConfiguration}).
 *
 * <p>Folds UTF-8 bytes into a fixed-length vector and L2-normalizes it. Identical texts produce
 * identical vectors (cosine 1.0); very different texts produce very different vectors. The
 * semantics are weak for semantic recall (a real embedding model wins there) but sufficient to
 * demonstrate the architecture and to run tests/offline without a provider dependency.
 *
 * <p>This is the stub previously duplicated verbatim across all four {@link
 * top.focess.veto.memory.MemoryStore} implementations, now extracted to a single place.
 */
public final class HashEmbedder implements Embedder {

    /** The fixed dimension this stub produces. */
    public static final int DIMENSION = 64;

    @Override
    public float @NonNull [] embed(@NonNull String text) {
        byte[] bytes = text.getBytes();
        float[] vec = new float[DIMENSION];
        for (int i = 0; i < bytes.length; i++) {
            vec[i % DIMENSION] += (bytes[i] & 0xff) / 255f;
        }
        // L2-normalize so cosine similarity is well-defined.
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

    @Override
    public int dimension() {
        return DIMENSION;
    }
}
