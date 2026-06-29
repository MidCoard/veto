package top.focess.veto.agent.mcp;

import org.jetbrains.annotations.NotNull;

/**
 * The result of executing a tool through the {@link McpEngine}.
 *
 * @param toolName the tool that was executed
 * @param callId matches the {@code ToolCall.callId} for provider tool_call_id pairing
 * @param success whether the tool call succeeded
 * @param content stdout / structured JSON response on success; error message on failure
 */
public record McpToolResult(
        @NotNull String toolName,
        @NotNull String callId,
        boolean success,
        @NotNull String content) {}
