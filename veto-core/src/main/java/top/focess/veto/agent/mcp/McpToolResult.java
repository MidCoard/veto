package top.focess.veto.agent.mcp;

/**
 * The result of executing a tool through the {@link McpEngine}. {@code
 * plans/mvp-core/part5_agent/mcp_tool_foundation.md}.
 *
 * @param toolName the tool that was executed
 * @param callId matches the {@code ToolCall.callId} for provider tool_call_id pairing
 * @param success whether the tool call succeeded
 * @param content stdout / structured JSON response on success; error message on failure
 */
public record McpToolResult(String toolName, String callId, boolean success, String content) {}
