package top.focess.veto.agent.mcp;

import org.jspecify.annotations.NonNull;

/**
 * The result of executing a tool through the {@link ToolEngine}.
 *
 * @param toolName the tool that was executed
 * @param callId matches the {@code ToolCall.callId} for provider tool_call_id pairing
 * @param status provider-independent execution status
 * @param format encoding of {@code content}
 * @param content exact tool output; presentation for the model is decided later
 * @param errorCode stable machine-readable failure code, or null when not applicable
 */
public record ToolResult(
        @NonNull String toolName,
        String callId,
        @NonNull ToolResultStatus status,
        @NonNull ToolResultFormat format,
        @NonNull String content,
        String errorCode) {

    /** Compatibility constructor for callers that do not yet declare a richer result contract. */
    public ToolResult(
            @NonNull String toolName, String callId, boolean success, @NonNull String content) {
        this(
                toolName,
                callId,
                success ? ToolResultStatus.SUCCESS : ToolResultStatus.FAILURE,
                ToolResultFormat.UNKNOWN,
                content,
                success ? null : "TOOL_FAILURE");
    }

    public boolean success() {
        return status == ToolResultStatus.SUCCESS;
    }

    public @NonNull ToolResult withContent(@NonNull String replacement) {
        return new ToolResult(toolName, callId, status, format, replacement, errorCode);
    }
}
