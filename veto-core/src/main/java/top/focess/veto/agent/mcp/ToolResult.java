package top.focess.veto.agent.mcp;

import org.jspecify.annotations.NonNull;

/**
 * The result of executing a tool through the {@link ToolEngine}.
 *
 * @param toolName the tool that was executed
 * @param callId matches the {@code ToolCall.callId} for provider tool_call_id pairing
 * @param success whether the tool call succeeded
 * @param content stdout / structured JSON response on success; error message on failure
 */
public record ToolResult(
        @NonNull String toolName,
        @NonNull String callId,
        boolean success,
        @NonNull String content) {}
