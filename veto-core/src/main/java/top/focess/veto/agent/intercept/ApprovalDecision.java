package top.focess.veto.agent.intercept;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.screening.Danger;

/**
 * The {@link HitlRegistry}'s decision for one tool call, computed from the {@link GatewayResult}
 * via the screening-mode matrix plus Session Rules. Only on {@link Prompt} does a veto pause
 * happen.
 *
 * <p>Sealed:
 *
 * <ul>
 *   <li>{@link AutoApprove#AUTO_APPROVE} — proceed with no round-trip.
 *   <li>{@link Prompt} — park the virtual thread; the user must resolve (carries the scenario +
 *       offered options).
 *   <li>{@link AutoBlock} — refuse outright (synthesized error observation; continue to next call).
 * </ul>
 */
public sealed interface ApprovalDecision
        permits ApprovalDecision.AutoApprove,
                ApprovalDecision.Prompt,
                ApprovalDecision.AutoBlock,
                ApprovalDecision.Refused {

    /** Singleton: proceed with the call, no HITL round-trip. */
    @NonNull AutoApprove AUTO_APPROVE = new AutoApprove();

    /** Proceed with the call, no HITL round-trip. */
    record AutoApprove() implements ApprovalDecision {}

    /** Refuse outright with a refusal notice. */
    record Refused(@NonNull String reason) implements ApprovalDecision {}

    /**
     * Park the virtual thread on a HITL future; the user must resolve via the veto endpoint.
     * Carries the screening {@link Danger} so transports can warn the user prominently when the
     * parked call is DANGEROUS/CRITICAL (null when no screening produced it, e.g. write-drift).
     */
    record Prompt(
            @NonNull VetoScenario scenario,
            @NonNull List<@NonNull VetoOption> options,
            Danger danger)
            implements ApprovalDecision {
        public Prompt {
            options = List.copyOf(options);
        }
    }

    /**
     * Refuse outright (a hard deterministic refusal); the agent gets a synthesized error
     * observation.
     */
    record AutoBlock(@NonNull String reason) implements ApprovalDecision {}
}
