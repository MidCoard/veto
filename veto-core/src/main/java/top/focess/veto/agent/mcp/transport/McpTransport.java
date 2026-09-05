package top.focess.veto.agent.mcp.transport;

import java.nio.file.Path;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolEngine;

/**
 * Transport descriptions for MCP endpoints. Only HTTP endpoints currently have an enforced
 * execution boundary; other modes are rejected before resource access.
 *
 * <p>Only <b>external</b> tools cross a real {@code McpTransport}. Native and agent tools are
 * dispatched directly in-process by the {@link ToolEngine} — there is no self-referential local MCP
 * server.
 */
public sealed interface McpTransport {

    /** Legacy stdio description. Rejected until sandboxed process execution is integrated. */
    record StdioMcpTransport(@NonNull ProcessBuilder processBuilder) implements McpTransport {}

    /** Remote or sidecar MCP servers exposing an HTTP endpoint with Server-Sent Events. */
    record SseMcpTransport(@NonNull String baseUrl, @NonNull String authToken)
            implements McpTransport {
        @Override
        public @NonNull String toString() {
            return "SseMcpTransport[credentials redacted]";
        }
    }

    /** Legacy socket description. Rejected until restricted socket access is integrated. */
    record SocketMcpTransport(@NonNull Path socketPath) implements McpTransport {}

    /** Remote Endpoint Mode : the tool runs on the user's local workstation. */
    record ClientDelegatedMcpTransport(@NonNull String channel) implements McpTransport {}
}
