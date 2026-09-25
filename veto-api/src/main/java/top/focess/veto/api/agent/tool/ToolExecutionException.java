package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;

/** Expected tool-level failure returned with {@code success=false} and a diagnostic body. */
public final class ToolExecutionException extends RuntimeException {

    /** Execution status carried into the structured result. */
    private final @NonNull ToolResultStatus status;

    /** Encoding of the model-visible diagnostic content. */
    private final @NonNull ToolResultFormat format;

    /** Stable machine-readable cause of the failure. */
    private final @NonNull ToolErrorCode errorCode;

    /**
     * Creates an expected tool failure for conversion into a structured result.
     *
     * @param status failure-like execution status
     * @param format encoding of the diagnostic content
     * @param errorCode stable machine-readable failure code
     * @param content model-visible diagnostic content
     */
    public ToolExecutionException(
            @NonNull ToolResultStatus status,
            @NonNull ToolResultFormat format,
            @NonNull ToolErrorCode errorCode,
            @NonNull String content) {
        super(content);
        this.status = status;
        this.format = format;
        this.errorCode = errorCode;
    }

    /**
     * Exposes the result status selected by the tool.
     *
     * @return the execution status to place on the tool result
     */
    public @NonNull ToolResultStatus status() {
        return status;
    }

    /**
     * Exposes the encoding selected for the diagnostic.
     *
     * @return the diagnostic content encoding
     */
    public @NonNull ToolResultFormat format() {
        return format;
    }

    /**
     * Exposes the stable failure classification.
     *
     * @return the stable machine-readable failure code
     */
    public @NonNull ToolErrorCode errorCode() {
        return errorCode;
    }

    /**
     * Produces the normalized model-visible diagnostic.
     *
     * @return non-blank model-visible diagnostic content
     */
    public @NonNull String content() {
        return ToolErrors.normalize(getMessage());
    }
}
