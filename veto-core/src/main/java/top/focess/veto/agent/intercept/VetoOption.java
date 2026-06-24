package top.focess.veto.agent.intercept;

/**
 * A resolution option offered at a veto prompt. The offered set is determined by the {@link
 * VetoScenario}.
 */
public enum VetoOption {
    // Scenario R (read)
    READ_MASK,
    READ_ALLOW,
    READ_DENY,
    // Scenario W (write drift)
    ABORT_WRITE,
    REREAD,
    FORCE_OVERWRITE,
    EDIT,
    // Scenario E1 (deterministic trip)
    BLOCK,
    OVERRIDE,
    // Scenario E2 (semantic flag)
    ACCEPT,
    ACCEPT_REDACTED,
    DECLINE,
    // Scenario E3 (first-time pattern)
    ACCEPT_ONCE,
    ACCEPT_AS_SESSION_RULE,
    // Per-call refuse-and-continue (inside the approve payload)
    DECLINE_AND_CONTINUE
}
