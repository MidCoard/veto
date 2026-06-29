package top.focess.veto.agent;

import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * The outcome of an agent task. Returned by {@code Agent.await} / {@code Agent.result} when the
 * loop completes (finished, breaker trip, or failure).
 *
 * @param success whether the task completed successfully
 * @param message the final user-facing text
 * @param metadata auxiliary info (turn count, breaker trip, etc.)
 */
public record AgentResult(
        boolean success, @NotNull String message, @NotNull Map<String, Object> metadata) {

    /** Convenience factory for a successful completion. */
    @NotNull
    public static AgentResult success(
            @NotNull String message, @NotNull Map<String, Object> metadata) {
        return new AgentResult(true, message, metadata);
    }

    /** Convenience factory for a failure / breaker trip. */
    @NotNull
    public static AgentResult failure(
            @NotNull String message, @NotNull Map<String, Object> metadata) {
        return new AgentResult(false, message, metadata);
    }
}
