package top.focess.veto.agent.intercept;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.screening.Screening;

/**
 * The {@link Gateway} screen result for one tool call. Sealed:
 *
 * <ul>
 *   <li>{@link Screened} — the normal (relevance, danger) screening result.
 *   <li>{@link DriftResult} — write-tool drift (Scenario W; a correctness check, not a danger
 *       class).
 *   <li>{@link NotScreened} — agent tools early-route past the Gateway.
 * </ul>
 */
public sealed interface GatewayResult
        permits GatewayResult.Screened, GatewayResult.DriftResult, GatewayResult.NotScreened {

    /** Filesystem targets captured by screening and bound to later execution. */
    @NonNull ToolExecutionPermit executionPermit();

    /** The normal screening result. */
    record Screened(@NonNull Screening screening, @NonNull ToolExecutionPermit executionPermit)
            implements GatewayResult {
        public Screened(@NonNull Screening screening) {
            this(screening, ToolExecutionPermit.empty());
        }
    }

    /** Write-tool drift — target changed since the agent last read it (Scenario W). */
    record DriftResult(
            @NonNull String path,
            @NonNull String diff,
            @NonNull ToolExecutionPermit executionPermit)
            implements GatewayResult {
        public DriftResult(@NonNull String path, @NonNull String diff) {
            this(path, diff, ToolExecutionPermit.empty());
        }
    }

    /** Agent tools early-route — no host-path params, not screened. */
    record NotScreened() implements GatewayResult {
        @Override
        public @NonNull ToolExecutionPermit executionPermit() {
            return ToolExecutionPermit.empty();
        }
    }
}
