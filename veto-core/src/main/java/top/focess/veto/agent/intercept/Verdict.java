package top.focess.veto.agent.intercept;

/**
 * The typed result of the {@link Gateway} screen for one native/remote tool call (LLD {@code
 * hybrid_loop_design.md} §3.2.1 step 4b, {@code gateway_security_api.md} §2). Not a boolean — the
 * {@link HitlRegistry} decides {@link ApprovalDecision} from it. Agent tools never produce a
 * verdict (they early-route past the Gateway).
 *
 * <p>Sealed:
 *
 * <ul>
 *   <li>{@link Safe#SAFE} — deterministic checks passed, no screening flag.
 *   <li>{@link Risky} — screening flagged the call (carries the {@link VetoScenario} + reason).
 *   <li>{@link Blocked} — a hard deterministic refusal (blacklisted, CRITICAL path class).
 *   <li>{@link Drift} — the write target changed since the agent last read it (Scenario W).
 * </ul>
 */
public sealed interface Verdict
        permits Verdict.Safe, Verdict.Risky, Verdict.Blocked, Verdict.Drift {

    /** Singleton: the call passed deterministic checks and raised no screening flag. */
    Safe SAFE = new Safe();

    /** Deterministic checks passed; no screening flag. */
    record Safe() implements Verdict {}

    /** The call was flagged — needs a human decision (carries the scenario + reason). */
    record Risky(VetoScenario scenario, String reason) implements Verdict {
        public Risky {
            if (scenario == null) {
                throw new IllegalArgumentException("scenario");
            }
        }
    }

    /** A hard deterministic refusal — never auto-approved, never covered by a session rule. */
    record Blocked(String reason) implements Verdict {}

    /** Write-tool drift — the target file changed since the agent last read it (Scenario W). */
    record Drift(String path, String diff) implements Verdict {
        public Drift {
            if (path == null) {
                path = "";
            }
            if (diff == null) {
                diff = "";
            }
        }
    }
}
