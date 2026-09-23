package top.focess.veto.api.agent.screening;

/**
 * Danger level — the risk of executing the call, fused max(SLM, deterministic). SLM omitted →
 * deterministic-only.
 */
public enum Danger {
    SAFE,
    ELEVATED,
    DANGEROUS,
    CRITICAL
}
