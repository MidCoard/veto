package top.focess.veto.agent.mcp;

import java.nio.file.Path;
import org.jspecify.annotations.NonNull;

/**
 * The transport a registered MCP server speaks. Four modes, selected at server registration time..
 *
 * <p>Only <b>external</b> tools cross a real {@code McpTransport}. Native and agent tools are
 * dispatched directly in-process by the {@link McpEngine} — there is no self-referential local MCP
 * server.
 */
public sealed interface McpTransport {

    /** Local sandboxed MCP servers launched as child processes (stdin/stdout pipes). */
    record StdioMcpTransport(@NonNull ProcessBuilder processBuilder) implements McpTransport {}

    /** Remote or sidecar MCP servers exposing an HTTP endpoint with Server-Sent Events. */
    record SseMcpTransport(@NonNull String baseUrl, @NonNull String authToken)
            implements McpTransport {}

    /** Containerized sandbox runtimes over a socket. */
    record SocketMcpTransport(@NonNull Path socketPath) implements McpTransport {}

    /** Remote Endpoint Mode : the tool runs on the user's local workstation. */
    record ClientDelegatedMcpTransport(@NonNull String channel) implements McpTransport {}
}
