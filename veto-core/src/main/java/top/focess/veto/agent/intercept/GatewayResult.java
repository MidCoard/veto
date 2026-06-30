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

    /** The normal screening result. */
    record Screened(@NonNull Screening screening) implements GatewayResult {}

    /** Write-tool drift — target changed since the agent last read it (Scenario W). */
    record DriftResult(@NonNull String path, @NonNull String diff) implements GatewayResult {}

    /** Agent tools early-route — no host-path params, not screened. */
    record NotScreened() implements GatewayResult {}
}
