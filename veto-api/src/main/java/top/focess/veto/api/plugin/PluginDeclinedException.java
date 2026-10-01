package top.focess.veto.api.plugin;

import org.jspecify.annotations.NonNull;

/**
 * An intentional decision during construction that this plugin cannot run in the current host. The
 * host withdraws its registrations and closes resources already registered with the context. If the
 * constructor throws this exception before returning, there is no plugin instance whose lifecycle
 * callbacks can run. Ordinary configuration mistakes and callback failures must use {@code
 * PluginFailure}; this exception is not a way to hide them.
 */
public final class PluginDeclinedException extends RuntimeException {
    /** Public, bounded reason for an intentional non-activation. */
    public enum Reason {
        /** A required host capability was not offered. */
        MISSING_HOST_SERVICE,
        /** The current operating environment is unsupported by this implementation. */
        UNSUPPORTED_ENVIRONMENT,
        /** This package is intentionally inapplicable under its operator configuration. */
        NOT_APPLICABLE
    }

    /** Stable reason retained without a plugin-private message or stack trace. */
    private final @NonNull Reason reason;

    /**
     * Creates a stackless decline without plugin-private details.
     *
     * @param reason public explanation of intentional non-activation
     */
    public PluginDeclinedException(@NonNull Reason reason) {
        super(reason.name(), null, false, false);
        this.reason = reason;
    }

    /**
     * Returns the public reason reported in the plugin inventory.
     *
     * @return the bounded non-activation reason
     */
    public @NonNull Reason reason() {
        return reason;
    }
}
