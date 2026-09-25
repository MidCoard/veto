package top.focess.veto.agent.workspace;

/** Per-root trust marker, read by the Gateway's path classification. */
public enum TrustMarker {
    /** A root owned by the workspace configuration. */
    OWNED,
    /** A shared root reached through an explicit grant. */
    SHARED_GRANT
}
