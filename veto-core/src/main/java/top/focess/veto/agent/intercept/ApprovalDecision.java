package top.focess.veto.agent.intercept;

import java.util.List;

/**
 * The {@link HitlRegistry}'s decision for one tool call, computed from the {@link Verdict} plus the
 * auto-approval policy and Session Rules. Only on {@link Prompt} does a veto pause happen.
 *
 * <p>Sealed:
 *
 * <ul>
 *   <li>{@link AutoApprove#AUTO_APPROVE} — proceed with no round-trip.
 *   <li>{@link Prompt} — park the virtual thread; the user must resolve (carries the scenario +
 *       offered options + the verdict payload).
 *   <li>{@link AutoBlock} — refuse outright (synthesized error observation; continue to next call).
 * </ul>
 */
public sealed interface ApprovalDecision
        permits ApprovalDecision.AutoApprove, ApprovalDecision.Prompt, ApprovalDecision.AutoBlock {

    /** Singleton: proceed with the call, no HITL round-trip. */
    AutoApprove AUTO_APPROVE = new AutoApprove();

    /** Proceed with the call, no HITL round-trip. */
    record AutoApprove() implements ApprovalDecision {}

    /** Park the virtual thread on a HITL future; the user must resolve via the veto endpoint. */
    record Prompt(VetoScenario scenario, List<VetoOption> options, Verdict verdict)
            implements ApprovalDecision {
        public Prompt {
            if (scenario == null) {
                throw new IllegalArgumentException("scenario");
            }
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /**
     * Refuse outright (e.g. {@link Verdict.Blocked}); the agent gets a synthesized error
     * observation.
     */
    record AutoBlock(String reason) implements ApprovalDecision {}
}
