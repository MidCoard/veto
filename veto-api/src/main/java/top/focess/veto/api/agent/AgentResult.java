package top.focess.veto.api.agent;

import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * The outcome of an agent task. Returned by {@code Agent.await} / {@code Agent.result} when the
 * loop completes (finished, breaker trip, or failure).
 *
 * @param success whether the task completed successfully
 * @param message the final user-facing text
 * @param metadata auxiliary info (turn count, breaker trip, etc.)
 */
public record AgentResult(
        boolean success, @NonNull String message, @NonNull Map<String, Object> metadata) {

    /**
     * Creates a successful task result.
     *
     * @param message final user-facing text
     * @param metadata auxiliary completion metadata
     * @return the successful result
     */
    public static @NonNull AgentResult success(
            @NonNull String message, @NonNull Map<String, Object> metadata) {
        return new AgentResult(true, message, metadata);
    }

    /**
     * Creates a failed task result, including breaker termination.
     *
     * @param message final user-facing failure text
     * @param metadata auxiliary failure metadata
     * @return the failed result
     */
    public static @NonNull AgentResult failure(
            @NonNull String message, @NonNull Map<String, Object> metadata) {
        return new AgentResult(false, message, metadata);
    }
}
