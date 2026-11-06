package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;

/**
 * The result of executing a tool through the the host tool engine.
 *
 * @param toolName the tool that was executed
 * @param callId matches the {@code ToolCall.callId} for provider tool_call_id pairing
 * @param status provider-independent execution status
 * @param format encoding of {@code content}
 * @param content canonical tool output before the session-specific representation is applied
 * @param errorCode stable machine-readable failure code, or null when not applicable; the wire and
 *     persisted representation is {@link ToolErrorCode#name()}
 * @param options implementation-owned options for the exact successful content
 */
public record ToolResult(
        @NonNull String toolName,
        String callId,
        @NonNull ToolResultStatus status,
        @NonNull ToolResultFormat format,
        @NonNull String content,
        ToolErrorCode errorCode,
        @NonNull ToolResultOptions options) {

    public ToolResult {
        if (status != ToolResultStatus.SUCCESS) options = ToolResultOptions.DEFAULT;
    }

    /** Creates a result with ordinary post-processing. */
    public ToolResult(
            @NonNull String toolName,
            String callId,
            @NonNull ToolResultStatus status,
            @NonNull ToolResultFormat format,
            @NonNull String content,
            ToolErrorCode errorCode) {
        this(toolName, callId, status, format, content, errorCode, ToolResultOptions.DEFAULT);
    }

    /**
     * Creates a successful result with no declared encoding and no error code.
     *
     * @param toolName executed tool name
     * @param callId provider call identifier, or {@code null}
     * @param content canonical result content
     * @return the successful result
     */
    public static @NonNull ToolResult success(
            @NonNull String toolName, String callId, @NonNull String content) {
        return new ToolResult(
                toolName,
                callId,
                ToolResultStatus.SUCCESS,
                ToolResultFormat.UNKNOWN,
                content,
                null);
    }

    /**
     * Creates a failed result with an explicit error code.
     *
     * @param toolName executed tool name
     * @param callId provider call identifier, or {@code null}
     * @param content canonical diagnostic content
     * @param errorCode stable machine-readable failure code
     * @return the failed result
     */
    public static @NonNull ToolResult failure(
            @NonNull String toolName,
            String callId,
            @NonNull String content,
            @NonNull ToolErrorCode errorCode) {
        return new ToolResult(
                toolName,
                callId,
                ToolResultStatus.FAILURE,
                ToolResultFormat.UNKNOWN,
                content,
                errorCode);
    }

    /**
     * Tests the status without requiring callers to compare enum constants.
     *
     * @return whether the host classified execution as successful
     */
    public boolean success() {
        return status == ToolResultStatus.SUCCESS;
    }

    /**
     * Copies this result, revoking processor exemptions when the content changes.
     *
     * @param replacement new canonical content
     * @return the copied result
     */
    public @NonNull ToolResult withContent(@NonNull String replacement) {
        return new ToolResult(
                toolName,
                callId,
                status,
                format,
                replacement,
                errorCode,
                content.equals(replacement) ? options : ToolResultOptions.DEFAULT);
    }

    /** Attaches trusted options only to successful results. */
    public @NonNull ToolResult withOptions(@NonNull ToolResultOptions selected) {
        return new ToolResult(
                toolName,
                callId,
                status,
                format,
                content,
                errorCode,
                success() ? selected : ToolResultOptions.DEFAULT);
    }
}
