package top.focess.veto.api.plugin;

/** Declared ownership level shared by plugin services and storage. */
public enum PluginScope {
    /** Plugin-wide scope; no user or session identity is conveyed to a service. */
    APPLICATION,
    /** A live host-issued user scope is required for every call. */
    USER,
    /** A live host-issued session scope is required for every call. */
    SESSION,
    /** A live host-attributed agent within a session is required for every call. */
    AGENT
}
