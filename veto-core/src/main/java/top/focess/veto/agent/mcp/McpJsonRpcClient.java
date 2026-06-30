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

    private static final Logger log = LoggerFactory.getLogger(McpJsonRpcClient.class);

    private final @NonNull ObjectMapper mapper;

    public McpJsonRpcClient() {
        this(new ObjectMapper());
    }

    public
    @NonNull
    McpJsonRpcClient(@NonNull ObjectMapper mapper) {
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
            // External tools default to NETWORK risk — maximum scrutiny at the Gateway. A
            // production deployer can register a tool with a finer risk via registerRemoteTool.
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
        Map<String, Object> params =
                Map.of("name", toolName, "arguments", args == null ? Map.of() : args);
        return invoke(transport, "tools/call", params, 60_000);
    }

    private JsonNode invoke(McpTransport transport, String method, Object params, long timeoutMs)
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
     * <p>The implementation uses reflection to keep the JDK classpath portable (Unix sockets are a
     * Linux/macOS runtime concern; the {@code java.net.UnixDomainSocketAddress} class exists in
     * Java 16+ but is only usable on POSIX systems).
     */
    private JsonNode invokeSocket(
            McpTransport.SocketMcpTransport transport, String method, Object params, long timeoutMs)
            throws IOException {
        if (!java.nio.file.Files.exists(transport.socketPath())) {
            throw new IOException("Socket MCP server not found at " + transport.socketPath());
        }
        try {
            Class<?> addrClass = Class.forName("java.net.UnixDomainSocketAddress");
            Class<?> channelClass = Class.forName("java.nio.channels.SocketChannel");
            Class<?> familiesClass = Class.forName("java.net.StandardProtocolFamily");
            Object unixFamily = familiesClass.getField("UNIX").get(null);
            java.lang.reflect.Method ofMethod = addrClass.getMethod("of", java.nio.file.Path.class);
            Object address = ofMethod.invoke(null, transport.socketPath());
            java.lang.reflect.Method openMethod = channelClass.getMethod("open", familiesClass);
            java.nio.channels.SocketChannel channel =
                    (java.nio.channels.SocketChannel) openMethod.invoke(null, unixFamily);
            try (java.nio.channels.SocketChannel ch = channel) {
                java.lang.reflect.Method connectMethod =
                        channelClass.getMethod("connect", java.net.SocketAddress.class);
                connectMethod.invoke(ch, address);
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
            }
        } catch (ClassNotFoundException e) {
            throw new IOException(
                    "Unix domain sockets are not supported on this JVM (need Java 16+).");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IOException("Socket MCP request failed: " + e.getMessage(), e);
        } catch (IllegalAccessException | NoSuchMethodException | NoSuchFieldException e) {
            throw new IOException("Unix domain socket reflection failed: " + e.getMessage(), e);
        }
    }

    private JsonNode invokeStdio(
            McpTransport.StdioMcpTransport transport, String method, Object params, long timeoutMs)
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

    private JsonNode invokeSse(
            McpTransport.SseMcpTransport transport, String method, Object params, long timeoutMs)
            throws IOException {
        try {
            HttpClient client =
                    HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
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
            HttpRequest httpRequest =
                    HttpRequest.newBuilder()
                            .uri(URI.create(transport.baseUrl()))
                            .timeout(Duration.ofMillis(timeoutMs))
                            .header("Content-Type", "application/json")
                            .header("Accept", "text/event-stream")
                            .header(
                                    "Authorization",
                                    transport.authToken() == null
                                            ? ""
                                            : "Bearer " + transport.authToken())
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
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
                    if (data.length() > 0) {
                        data.append("\n");
                    }
                    data.append(line.substring("data:".length()).strip());
                }
            }
            if (data.length() == 0) {
                throw new IOException("SSE MCP server returned no data");
            }
            return parseResponse(data.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("SSE MCP request interrupted", e);
        }
    }

    private JsonNode parseResponse(String line) throws IOException {
        JsonNode node = mapper.readTree(line);
        JsonNode error = node.get("error");
        if (error != null) {
            throw new IOException("MCP server error: " + error.toString());
        }
        JsonNode result = node.get("result");
        if (result == null) {
            throw new IOException("MCP server response missing 'result'");
        }
        return result;
    }

    private static String serverNameFor(McpTransport transport) {
        return switch (transport) {
            case McpTransport.StdioMcpTransport s -> "stdio:" + s.processBuilder().command();
            case McpTransport.SseMcpTransport s -> "sse:" + s.baseUrl();
            case McpTransport.SocketMcpTransport s -> "socket:" + s.socketPath();
            case McpTransport.ClientDelegatedMcpTransport s -> "client:" + s.channel();
        };
    }
}
