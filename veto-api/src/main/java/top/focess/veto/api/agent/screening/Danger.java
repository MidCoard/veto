package top.focess.veto.api.agent.screening;

/**
 * Danger level — the risk of executing the call, fused max(SLM, deterministic). SLM omitted →
 * deterministic-only.
 */
public enum Danger {
    /** No material risk identified by current screening. */
    SAFE,
    /** A meaningful but normally recoverable effect requires added scrutiny. */
    ELEVATED,
    /** The effect can cause substantial loss or external impact. */
    DANGEROUS,
    /** The effect has the highest risk and requires the strongest intervention. */
    CRITICAL
}
