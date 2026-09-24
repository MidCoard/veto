package top.focess.veto.api.llm;

import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * Host-local completion. Callers compile MDC with PromptRenderer and own response interpretation.
 */
public interface LocalModelCompletion {
    /** Purpose is local to the calling plugin; grammar is literal GBNF, never a file path. */
    record Request(@NonNull String purpose, @NonNull String prompt, @NonNull String grammar) {}

    boolean isAvailable();

    /**
     * Host bounds input, output, duration and lifecycle; unavailable/cancelled calls yield empty.
     */
    @NonNull Optional<String> complete(@NonNull Request request);
}
