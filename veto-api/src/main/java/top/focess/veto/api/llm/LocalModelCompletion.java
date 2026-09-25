package top.focess.veto.api.llm;

import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * Host-local completion. Callers compile MDC with PromptRenderer and own response interpretation.
 */
public interface LocalModelCompletion {
    /**
     * One bounded local-model request.
     *
     * @param purpose caller-defined diagnostic purpose
     * @param prompt complete model input
     * @param grammar literal GBNF grammar, never a file path
     */
    record Request(@NonNull String purpose, @NonNull String prompt, @NonNull String grammar) {}

    /**
     * Checks whether the host currently offers local completion.
     *
     * @return whether the host currently has a local completion implementation available
     */
    boolean isAvailable();

    /**
     * Host bounds input, output, duration and lifecycle; unavailable or cancelled calls yield
     * empty. A present value is raw generated text and still requires caller interpretation.
     *
     * @param request bounded local-model request
     * @return raw generated text, or empty when unavailable or cancelled
     */
    @NonNull Optional<String> complete(@NonNull Request request);
}
