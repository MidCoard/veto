package top.focess.veto.api.plugin.service;

import org.jspecify.annotations.NonNull;

/** Safe cross-plugin failure; provider diagnostics and causes stay private. */
public final class ServiceException extends Exception {
    public enum Code {
        UNAVAILABLE,
        INVALID_REQUEST,
        TIMEOUT,
        FAILED
    }

    private final @NonNull Code code;

    public ServiceException(@NonNull Code code) {
        super(code.name(), null, false, false);
        this.code = code;
    }

    public @NonNull Code code() {
        return code;
    }
}
