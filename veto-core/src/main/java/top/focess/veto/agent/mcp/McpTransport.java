package top.focess.veto.agent.mcp;

import java.nio.file.Path;

/**
 * The transport a registered MCP server speaks. Four modes, selected at server registration time..
 *
 * <p>Only <b>external</b> tools cross a real {@code McpTransport}. Native and agent tools are
 * dispatched directly in-process by the {@link McpEngine} — there is no self-referential local MCP
 * server.
 */
public sealed interface McpTransport {

    /** Local sandboxed MCP servers launched as child processes (stdin/stdout pipes). */
    record StdioMcpTransport(ProcessBuilder processBuilder) implements McpTransport {}

    /** Remote or sidecar MCP servers exposing an HTTP endpoint with Server-Sent Events. */
    record SseMcpTransport(String baseUrl, String authToken) implements McpTransport {}

    /** Containerized sandbox runtimes over a socket. */
    record SocketMcpTransport(Path socketPath) implements McpTransport {}

    /** Remote Endpoint Mode : the tool runs on the user's local workstation. */
    record ClientDelegatedMcpTransport(String channel) implements McpTransport {}
}
