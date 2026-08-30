package top.focess.veto.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A minimal JSON-RPC 2.0 client for talking to remote MCP servers. Supports the {@code tools/list}
 * discovery and {@code tools/call} invocation over both stdio (subprocess stdin/stdout JSON lines)
 * and SSE (HTTP POST + Server-Sent-Events) transports.
 *
 * <p>This is the production path for the Part 5 "real remote-MCP JSON-RPC tools/list discovery +
 * stdio/SSE/socket execution" item (mcp_tool_foundation.md §10.7). The client is intentionally
 * small: it only implements the methods the Veto engine needs ({@code tools/list} + {@code
 * tools/call}); it does NOT implement notifications, sampling, roots, or any of the optional MCP
 * capabilities.
 */
public final class McpJsonRpcClient {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.mcp.McpJsonRpcClient");

    private final @NonNull ObjectMapper mapper;

    public McpJsonRpcClient() {
        this(new ObjectMapper());
    }

    public McpJsonRpcClient(@NonNull ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Discovers the list of tools from the server. */
    public @NonNull List<RemoteToolDefinition> discoverTools(@NonNull McpTransport transport)
            throws IOException {
        JsonNode response = invoke(transport, "tools/list", null, 30_000);
        JsonNode tools = response.get("tools");
        if (tools == null || !tools.isArray()) {
            return List.of();
        }
        List<RemoteToolDefinition> out = new ArrayList<>();
        for (JsonNode t : tools) {
            String name = t.path("name").asText();
            String description = t.path("description").asText("");
            // The schema is the raw JSON Schema; RemoteToolDefinition stores it as JsonNode.
            JsonNode inputSchema = t.path("inputSchema");
            String serverName = serverNameFor(transport);
            // Unclassified external tools stay REMOTE_UNKNOWN with NETWORK screening risk. A
            // server description cannot downgrade this contract.
            out.add(
                    new RemoteToolDefinition(
                            name, description, RiskCategory.NETWORK, serverName, inputSchema));
        }
        return out;
    }

    /** Calls a tool on the server and returns the raw JSON result. */
    public @NonNull JsonNode callTool(
            @NonNull McpTransport transport,
            @NonNull String toolName,
            @NonNull Map<String, Object> args)
            throws IOException {
        Map<String, Object> params = Map.of("name", toolName, "arguments", args);
        return invoke(transport, "tools/call", params, 60_000);
    }

    private @NonNull JsonNode invoke(
            @NonNull McpTransport transport, @NonNull String method, Object params, long timeoutMs)
            throws IOException {
        return switch (transport) {
            case McpTransport.StdioMcpTransport stdio ->
                    invokeStdio(stdio, method, params, timeoutMs);
            case McpTransport.SseMcpTransport sse -> invokeSse(sse, method, params, timeoutMs);
            case McpTransport.SocketMcpTransport socket ->
                    invokeSocket(socket, method, params, timeoutMs);
            case McpTransport.ClientDelegatedMcpTransport delegated ->
                    throw new IOException(
                            "Client-delegated transport is a UI-channel transport, not a JSON-RPC transport: "
                                    + delegated);
        };
    }

    /**
     * JSON-RPC over a Unix domain socket. The transport writes one request line (newline-delimited
     * JSON) to the socket and reads one response line. Container-sandbox sockets (Linux/macOS) use
     * the {@code java.net.UnixDomainSocketAddress} path; Windows hosts don't have unix sockets and
     * the transport returns an {@code IOException}.
     *
     * <p>Unix-domain socket types are part of the supported Java baseline. Platforms without Unix
     * sockets fail with a descriptive {@link IOException}.
     */
    private @NonNull JsonNode invokeSocket(
            McpTransport.@NonNull SocketMcpTransport transport,
            @NonNull String method,
            Object params,
            long timeoutMs)
            throws IOException {
        if (!java.nio.file.Files.exists(transport.socketPath())) {
            throw new IOException("Socket MCP server not found at " + transport.socketPath());
        }
        try (java.nio.channels.SocketChannel ch =
                java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            java.net.UnixDomainSocketAddress address =
                    java.net.UnixDomainSocketAddress.of(transport.socketPath());
            ch.connect(address);
            Map<String, Object> request =
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            1,
                            "method",
                            method,
                            "params",
                            params == null ? Map.of() : params);
            byte[] body = (mapper.writeValueAsString(request) + "\n").getBytes();
            java.nio.ByteBuffer out = java.nio.ByteBuffer.wrap(body);
            while (out.hasRemaining()) {
                ch.write(out);
            }
            StringBuilder in = new StringBuilder();
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(8192);
            long deadline =
                    System.nanoTime()
                            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (System.nanoTime() < deadline) {
                buf.clear();
                int n = ch.read(buf);
                if (n < 0) {
                    break;
                }
                if (n > 0) {
                    buf.flip();
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    String chunk = new String(bytes);
                    in.append(chunk);
                    int newline = in.indexOf("\n");
                    if (newline >= 0) {
                        return parseResponse(in.substring(0, newline));
                    }
                }
            }
            throw new IOException("Socket MCP server timed out");
        } catch (UnsupportedOperationException e) {
            throw new IOException("Unix domain sockets are not supported on this platform", e);
        }
    }

    private @NonNull JsonNode invokeStdio(
            McpTransport.@NonNull StdioMcpTransport transport,
            @NonNull String method,
            Object params,
            long timeoutMs)
            throws IOException {
        Process p;
        try {
            p = transport.processBuilder().start();
        } catch (IOException e) {
            throw new IOException("Failed to start stdio MCP server", e);
        }
        try {
            Map<String, Object> request =
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            1,
                            "method",
                            method,
                            "params",
                            params == null ? Map.of() : params);
            String line = mapper.writeValueAsString(request);
            p.getOutputStream().write((line + "\n").getBytes());
            p.getOutputStream().flush();
            // Read one response line (simplified — the server may emit notifications on the same
            // stream; a production client would demultiplex).
            String responseLine;
            try (var reader =
                    new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
                while ((responseLine = reader.readLine()) != null) {
                    if (responseLine.startsWith("{")) {
                        break;
                    }
                    if (System.nanoTime() > deadline) {
                        throw new IOException("stdio MCP server timed out");
                    }
                }
            }
            if (responseLine == null) {
                throw new IOException("stdio MCP server returned no response");
            }
            return parseResponse(responseLine);
        } finally {
            p.destroy();
        }
    }

    private @NonNull JsonNode invokeSse(
            McpTransport.@NonNull SseMcpTransport transport,
            @NonNull String method,
            Object params,
            long timeoutMs)
            throws IOException {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build()) {
            Map<String, Object> request =
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            1,
                            "method",
                            method,
                            "params",
                            params == null ? Map.of() : params);
            String body = mapper.writeValueAsString(request);
            HttpRequest.Builder requestBuilder =
                    HttpRequest.newBuilder()
                            .uri(URI.create(transport.baseUrl()))
                            .timeout(Duration.ofMillis(timeoutMs))
                            .header("Content-Type", "application/json")
                            .header("Accept", "text/event-stream");
            String authToken = transport.authToken();
            if (!authToken.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + authToken);
            }
            HttpRequest httpRequest =
                    requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response =
                    client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException(
                        "SSE MCP server returned "
                                + response.statusCode()
                                + ": "
                                + response.body());
            }
            // SSE response: each event is a "data: <json>\n\n" block. Read the first data line.
            StringBuilder data = new StringBuilder();
            for (String line : response.body().split("\n")) {
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append("\n");
                    }
                    data.append(line.substring("data:".length()).strip());
                }
            }
            if (data.isEmpty()) {
                throw new IOException("SSE MCP server returned no data");
            }
            return parseResponse(data.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("SSE MCP request interrupted", e);
        }
    }

    private @NonNull JsonNode parseResponse(@NonNull String line) throws IOException {
        JsonNode node = mapper.readTree(line);
        JsonNode error = node.get("error");
        if (error != null) {
            throw new IOException("MCP server error: " + error);
        }
        JsonNode result = node.get("result");
        if (result == null) {
            throw new IOException("MCP server response missing 'result'");
        }
        return result;
    }

    private static @NonNull String serverNameFor(@NonNull McpTransport transport) {
        return switch (transport) {
            case McpTransport.StdioMcpTransport s -> "stdio:" + s.processBuilder().command();
            case McpTransport.SseMcpTransport s -> "sse:" + s.baseUrl();
            case McpTransport.SocketMcpTransport s -> "socket:" + s.socketPath();
            case McpTransport.ClientDelegatedMcpTransport s -> "client:" + s.channel();
        };
    }
}
