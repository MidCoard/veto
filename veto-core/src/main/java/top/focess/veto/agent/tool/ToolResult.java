package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/**
 * The result of executing a tool through the {@link ToolEngine}.
 *
 * @param toolName the tool that was executed
 * @param callId matches the {@code ToolCall.callId} for provider tool_call_id pairing
 * @param status provider-independent execution status
 * @param format encoding of {@code content}
 * @param content canonical tool output before the session-specific representation is applied
 * @param errorCode stable machine-readable failure code, or null when not applicable; the wire and
 *     persisted representation is {@link ToolErrorCode#name()}
 */
public record ToolResult(
        @NonNull String toolName,
        String callId,
        @NonNull ToolResultStatus status,
        @NonNull ToolResultFormat format,
        @NonNull String content,
        ToolErrorCode errorCode) {

    /** A successful result with no declared encoding; success carries no error code. */
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

    /** A failed result; failures always carry an explicit error code. */
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

    public boolean success() {
        return status == ToolResultStatus.SUCCESS;
    }

    public @NonNull ToolResult withContent(@NonNull String replacement) {
        return new ToolResult(toolName, callId, status, format, replacement, errorCode);
    }
}
