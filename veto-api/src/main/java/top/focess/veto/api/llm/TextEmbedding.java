package top.focess.veto.api.llm;

import org.jspecify.annotations.NonNull;

/** Host-configured embedding model. The host binds each call to the current plugin invocation. */
public interface TextEmbedding {
    /**
     * Computes an embedding using the host-configured model.
     *
     * @param text text to embed
     * @return vector whose length is {@link #dimension()}
     */
    float @NonNull [] embed(@NonNull String text);

    /**
     * Reports the fixed output-vector size of this model.
     *
     * @return the fixed vector dimension produced by this configured model
     */
    int dimension();
}
