package top.focess.veto.extension.contract;

import org.jspecify.annotations.NonNull;

/** Safe failure contract: no free-form message, cause, configuration or input payload. */
public final class ExtensionFailure extends Exception {
    public enum Code {
        INVALID_CONFIGURATION,
        NOT_READY,
        CANCELLED,
        INVALID_ARGUMENTS,
        INTERNAL_FAILURE
    }

    private final @NonNull Code code;

    public ExtensionFailure(@NonNull Code code) {
        super(code.name(), null, false, false);
        this.code = code;
    }

    public @NonNull Code code() {
        return code;
    }
}
