package top.focess.veto.agent.workspace;

/** How the agent expresses paths. Set by deployer policy. */
public enum PathMode {
    /** Agent uses virtual paths under `/`; resolver maps to a root by prefix. */
    VIRTUAL,
    /** Agent uses real host paths; resolver canonicalizes + finds the containing root. */
    REAL
}
