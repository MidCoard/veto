package top.focess.veto.agent.screening;

/**
 * Install-time deployer policy. FULL: no protected set. PROTECT_SENSITIVE: protected set exists.
 */
public enum DeployerPolicy {
    FULL_ACCESS,
    PROTECTED,
    SANDBOXED,
    TENANT
}
