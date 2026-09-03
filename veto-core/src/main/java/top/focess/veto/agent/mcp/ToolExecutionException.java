package top.focess.veto.agent.mcp;

import org.jspecify.annotations.NonNull;

/** Expected tool-level failure returned with {@code success=false} and a diagnostic body. */
@SuppressWarnings("serial")
public final class ToolExecutionException extends RuntimeException {

    private final @NonNull ToolResultStatus status;
    private final @NonNull ToolResultFormat format;
    private final @NonNull String errorCode;

    public ToolExecutionException(@NonNull String message) {
        this(ToolResultStatus.FAILURE, ToolResultFormat.PLAINTEXT, "TOOL_FAILURE", message);
    }

    public ToolExecutionException(
            @NonNull ToolResultStatus status,
            @NonNull ToolResultFormat format,
            @NonNull String errorCode,
            @NonNull String content) {
        super(content);
        this.status = status;
        this.format = format;
        this.errorCode = errorCode;
    }

    public @NonNull ToolResultStatus status() {
        return status;
    }

    public @NonNull ToolResultFormat format() {
        return format;
    }

    public @NonNull String errorCode() {
        return errorCode;
    }

    public @NonNull String content() {
        return ToolErrors.normalize(getMessage());
    }
}
