package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;

/** Helpers for the special plaintext tool-failure channel. */
public final class ToolErrors {

    private ToolErrors() {}

    /**
     * Throws an expected failure with a stable machine-readable code.
     *
     * @param <T> inferred unreachable return type
     * @param errorCode stable failure code
     * @param message diagnostic text, normalized when blank
     * @return never returns
     */
    public static <T> T failure(@NonNull ToolErrorCode errorCode, String message) {
        throw new ToolExecutionException(
                ToolResultStatus.FAILURE,
                ToolResultFormat.PLAINTEXT,
                errorCode,
                normalize(message));
    }

    /**
     * Throws an expected refusal with a stable machine-readable code.
     *
     * @param <T> inferred unreachable return type
     * @param errorCode stable refusal code
     * @param message diagnostic text, normalized when blank
     * @return never returns
     */
    public static <T> T refused(@NonNull ToolErrorCode errorCode, String message) {
        throw new ToolExecutionException(
                ToolResultStatus.REFUSED,
                ToolResultFormat.PLAINTEXT,
                errorCode,
                normalize(message));
    }

    /**
     * Produces the non-blank diagnostic used by the plaintext failure channel.
     *
     * @param message proposed diagnostic, possibly {@code null} or blank
     * @return the message or a generic non-blank diagnostic
     */
    public static @NonNull String normalize(String message) {
        return message == null || message.isBlank()
                ? "Unexpected error with no diagnostic message"
                : message;
    }
}
