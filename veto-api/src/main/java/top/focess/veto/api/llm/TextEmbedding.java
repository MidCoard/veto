package top.focess.veto.api.llm;

import org.jspecify.annotations.NonNull;

/** Host-configured embedding model. The host binds each call to the current plugin invocation. */
public interface TextEmbedding {
    float @NonNull [] embed(@NonNull String text);

    int dimension();
}
