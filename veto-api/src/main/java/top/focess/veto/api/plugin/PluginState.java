package top.focess.veto.api.plugin;

/** Host-observed lifecycle state, or catalog state for a disabled installed package. */
public enum PluginState {
    /** Lifecycle binding has not begun; an installed entry may not yet have an instance. */
    NEW,
    /** The host is constructing the entry or binding its context and registrations. */
    INITIALIZING,
    /** Construction and context binding succeeded; {@link VetoPlugin#start()} has not run. */
    INITIALIZED,
    /** The host is invoking {@link VetoPlugin#start}. */
    STARTING,
    /** Startup succeeded and the plugin may admit work. */
    ACTIVE,
    /** New admission is closed and existing work is draining. */
    STOPPING,
    /** Cleanup has completed and the instance cannot be reused. */
    CLOSED,
    /** Construction intentionally declined; cleanup completed without publishing contributions. */
    DECLINED,
    /** Catalog state for a package disabled at backend startup; no plugin instance was created. */
    DISABLED,
    /** A lifecycle callback failed; the host still attempts cleanup. */
    FAILED
}
