package top.focess.veto.api.plugin.contract;

import org.jspecify.annotations.NonNull;

/** Safe failure contract: no free-form message, cause, configuration or input payload. */
public final class PluginFailure extends Exception {
    /** Stable failure codes safe to expose without plugin diagnostics. */
    public enum Code {
        /** Plugin configuration is invalid. */
        INVALID_CONFIGURATION,
        /** Required plugin state or host service is not ready. */
        NOT_READY,
        /** The operation observed cooperative cancellation. */
        CANCELLED,
        /** Invocation arguments violate the plugin contract. */
        INVALID_ARGUMENTS,
        /** Unexpected plugin failure with details intentionally hidden. */
        INTERNAL_FAILURE
    }

    /** Sanitized failure code exposed across the plugin boundary. */
    private final @NonNull Code code;

    /**
     * Creates a stackless failure carrying only a stable public code.
     *
     * @param code stable public failure code
     */
    public PluginFailure(@NonNull Code code) {
        super(code.name(), null, false, false);
        this.code = code;
    }

    /**
     * Returns the failure classification safe to expose to callers.
     *
     * @return the stable public failure code
     */
    public @NonNull Code code() {
        return code;
    }
}
