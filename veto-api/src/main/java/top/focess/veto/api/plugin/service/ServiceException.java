package top.focess.veto.api.plugin.service;

import org.jspecify.annotations.NonNull;

/** Safe cross-plugin failure; provider diagnostics and causes stay private. */
public final class ServiceException extends Exception {
    /** Stable failure code safe to expose across the plugin boundary. */
    public enum Code {
        /** The provider or caller is no longer admitted or visible. */
        UNAVAILABLE,
        /** The request does not satisfy the provider protocol. */
        INVALID_REQUEST,
        /** Invocation exceeded the host or protocol deadline. */
        TIMEOUT,
        /** The provider failed without a more specific public code. */
        FAILED
    }

    /** Portable named-service failure code. */
    private final @NonNull Code code;

    /**
     * Creates a stackless public failure with no provider cause or diagnostic text.
     *
     * @param code stable public failure code
     */
    public ServiceException(@NonNull Code code) {
        super(code.name(), null, false, false);
        this.code = code;
    }

    /**
     * Returns the failure classification safe to expose across plugins.
     *
     * @return the stable public failure code
     */
    public @NonNull Code code() {
        return code;
    }
}
