package top.focess.veto.vault;

import org.jspecify.annotations.NonNull;

/** An authentication rejection that transports render without changing its business meaning. */
public final class AuthException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Transport-independent failure categories. */
    public enum Kind {
        INVALID_INPUT,
        INVALID_CREDENTIALS,
        INVALID_SESSION,
        FORBIDDEN,
        CONFLICT,
        INTERNAL
    }

    private final @NonNull Kind kind;
    private final @NonNull String message;

    public AuthException(@NonNull Kind kind, @NonNull String message) {
        this(kind, message, null);
    }

    public AuthException(@NonNull Kind kind, @NonNull String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.message = message;
    }

    public @NonNull Kind kind() {
        return kind;
    }

    @Override
    public @NonNull String getMessage() {
        return message;
    }
}
