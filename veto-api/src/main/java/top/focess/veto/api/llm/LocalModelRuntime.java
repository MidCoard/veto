package top.focess.veto.api.llm;

import org.jspecify.annotations.NonNull;

/** Host-owned local model processes shared by providers and core SLM consumers. */
public interface LocalModelRuntime {
    /**
     * Opens a filesystem model with the requested context size and returns its loopback API. Reuses
     * the same canonical artifact/context instance; never accepts a network endpoint.
     */
    @NonNull String endpoint(@NonNull String modelPath, int contextTokens);
}
