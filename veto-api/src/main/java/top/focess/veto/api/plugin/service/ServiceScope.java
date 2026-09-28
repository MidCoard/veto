package top.focess.veto.api.plugin.service;

/** The host-validated identity boundary required for one named service. */
public enum ServiceScope {
    /** No user or session identity is conveyed. */
    GLOBAL,
    /** A live host-issued user scope is required for every call. */
    USER,
    /** A live host-issued session scope is required for every call. */
    SESSION
}
