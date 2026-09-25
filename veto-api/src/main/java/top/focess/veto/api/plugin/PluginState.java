package top.focess.veto.api.plugin;

/** Host-observed state of one installed plugin instance. */
public enum PluginState {
    /** The instance has been constructed but initialization has not begun. */
    NEW,
    /** The host is invoking {@link VetoPlugin#initialize}. */
    INITIALIZING,
    /** Contributions were validated, but the plugin has not started. */
    INITIALIZED,
    /** The host is invoking {@link VetoPlugin#start}. */
    STARTING,
    /** Startup succeeded and the plugin may admit work. */
    ACTIVE,
    /** New admission is closed and existing work is draining. */
    STOPPING,
    /** Cleanup has completed and the instance cannot be reused. */
    CLOSED,
    /** A lifecycle callback failed; the host still attempts cleanup. */
    FAILED
}
