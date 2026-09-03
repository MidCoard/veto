package top.focess.veto.agent.mcp;

import org.jspecify.annotations.NonNull;

/** Helpers for the special plaintext tool-failure channel. */
public final class ToolErrors {

    private ToolErrors() {}

    /** Throws an expected tool failure while preserving the enclosing method's return type. */
    public static <T> T failure(String message) {
        throw new ToolExecutionException(normalize(message));
    }

    /** Throws an expected failure with a stable machine-readable code. */
    public static <T> T failure(@NonNull String errorCode, String message) {
        throw new ToolExecutionException(
                ToolResultStatus.FAILURE,
                ToolResultFormat.PLAINTEXT,
                errorCode,
                normalize(message));
    }

    /** Throws an expected refusal with a stable machine-readable code. */
    public static <T> T refused(@NonNull String errorCode, String message) {
        throw new ToolExecutionException(
                ToolResultStatus.REFUSED,
                ToolResultFormat.PLAINTEXT,
                errorCode,
                normalize(message));
    }

    /** Produces the non-blank diagnostic used by the special plaintext failure channel. */
    public static @NonNull String normalize(String message) {
        return message == null || message.isBlank()
                ? "Unexpected error with no diagnostic message"
                : message;
    }
}
