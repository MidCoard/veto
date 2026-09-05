package top.focess.veto.agent.mcp.transport;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.RemoteToolDefinition;

/**
 * A minimal JSON-RPC 2.0 client for talking to remote MCP servers. Supports the {@code tools/list}
 * discovery and {@code tools/call} invocation over both stdio (subprocess stdin/stdout JSON lines)
 * and SSE (HTTP requestBuilder.POST + Server-Sent-Events) transports.
 *
 * <p>The client intentionally implements only the MCP methods Veto needs ({@code tools/list} and
 * {@code tools/call}); it does not implement notifications, sampling, roots, or other optional MCP
 * capabilities.
 */
public final class McpJsonRpcClient {

    private final @NonNull ObjectMapper mapper;
    private final @NonNull ObjectReader responseReader;
    private final long discoveryTimeoutMs;
    private final long callTimeoutMs;

    public McpJsonRpcClient() {
        this(new ObjectMapper());
    }

    public McpJsonRpcClient(@NonNull ObjectMapper mapper) {
        this(mapper, Duration.ofSeconds(30), Duration.ofSeconds(60));
    }

    public McpJsonRpcClient(
            @NonNull ObjectMapper mapper,
            @NonNull Duration discoveryTimeout,
            @NonNull Duration callTimeout) {
        this.mapper = mapper;
        this.responseReader =
                mapper.reader()
                        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.discoveryTimeoutMs = discoveryTimeout.toMillis();
        this.callTimeoutMs = callTimeout.toMillis();
        if (discoveryTimeoutMs < 1 || callTimeoutMs < 1) {
            throw new IllegalArgumentException("MCP timeouts must be at least one millisecond");
        }
    }

    /** Discovers the list of tools from the server. */
    public @NonNull List<RemoteToolDefinition> discoverTools(@NonNull McpTransport transport)
            throws IOException {
        JsonNode response = invoke(transport, "tools/list", null, discoveryTimeoutMs);
        JsonNode tools = response.get("tools");
        if (tools == null || !tools.isArray()) {
            throw new IOException("MCP tools/list result must contain a tools array");
        }
        List<RemoteToolDefinition> out = new ArrayList<>();
        String serverName = "mcp-" + UUID.randomUUID();
        for (JsonNode t : tools) {
            JsonNode nameNode = t.get("name");
            JsonNode descriptionNode = t.get("description");
            JsonNode inputSchema = t.get("inputSchema");
            if (nameNode == null
                    || !nameNode.isTextual()
                    || nameNode.asText().isBlank()
                    || inputSchema == null
                    || !inputSchema.isObject()
                    || !"object".equals(inputSchema.path("type").asText())
                    || (descriptionNode != null && !descriptionNode.isTextual())) {
                throw new IOException(
                        "Invalid MCP tool declaration: name and object inputSchema are required");
            }
            String name = nameNode.asText();
            String description = descriptionNode == null ? "" : descriptionNode.asText();
            // Unclassified external tools stay REMOTE_UNKNOWN with an ELEVATED danger floor. A
            // server description cannot downgrade this contract.
            out.add(new RemoteToolDefinition(name, description, serverName, inputSchema));
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
        return invoke(transport, "tools/call", params, callTimeoutMs);
    }

    private @NonNull JsonNode invoke(
            @NonNull McpTransport transport, @NonNull String method, Object params, long timeoutMs)
            throws IOException {
        String body =
                mapper.writeValueAsString(
                        Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                1,
                                "method",
                                method,
                                "params",
                                params == null ? Map.of() : params));
        return switch (transport) {
            case McpTransport.StdioMcpTransport stdio -> invokeStdio(stdio, body, timeoutMs);
            case McpTransport.SseMcpTransport sse -> invokeSse(sse, body, timeoutMs);
            case McpTransport.SocketMcpTransport socket -> invokeSocket(socket, body, timeoutMs);
            case McpTransport.ClientDelegatedMcpTransport delegated ->
                    throw new IOException(
                            "Client-delegated transport is a UI-channel transport, not a JSON-RPC transport: "
                                    + delegated);
        };
    }

    /**
     * JSON-RPC over a Unix domain socket. The transport writes one request line (newline-delimited
     * JSON) to the socket and reads one response line. Container-sandbox sockets (Linux/macOS) use
     * the {@code UnixDomainSocketAddress} path.
     *
     * <p>Unix-domain socket types are part of the supported Java baseline. Platforms without Unix
     * sockets fail with a descriptive {@link IOException}.
     */
    private @NonNull JsonNode invokeSocket(
            McpTransport.@NonNull SocketMcpTransport transport,
            @NonNull String body,
            long timeoutMs)
            throws IOException {
        if (!Files.exists(transport.socketPath())) {
            throw new IOException("Socket MCP server not found at " + transport.socketPath());
        }
        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            return withDeadline(
                    () -> {
                        UnixDomainSocketAddress address =
                                UnixDomainSocketAddress.of(transport.socketPath());
                        ch.connect(address);
                        byte[] bytes = (body + "\n").getBytes(StandardCharsets.UTF_8);
                        ByteBuffer out = ByteBuffer.wrap(bytes);
                        while (out.hasRemaining()) {
                            ch.write(out);
                        }
                        return readResponse(Channels.newInputStream(ch));
                    },
                    timeoutMs);
        } catch (UnsupportedOperationException e) {
            throw new IOException("Unix domain sockets are not supported on this platform", e);
        }
    }

    private @NonNull JsonNode invokeStdio(
            McpTransport.@NonNull StdioMcpTransport transport, @NonNull String body, long timeoutMs)
            throws IOException {
        Process p;
        try {
            p = transport.processBuilder().start();
        } catch (IOException e) {
            throw new IOException("Failed to start stdio MCP server", e);
        }
        try {
            return withDeadline(
                    () -> {
                        p.getOutputStream().write((body + "\n").getBytes(StandardCharsets.UTF_8));
                        p.getOutputStream().flush();
                        return readResponse(p.getInputStream());
                    },
                    timeoutMs);
        } finally {
            p.destroy();
        }
    }

    private static @NonNull JsonNode withDeadline(
            @NonNull Callable<@NonNull JsonNode> operation, long timeoutMs) throws IOException {
        FutureTask<@NonNull JsonNode> task = new FutureTask<>(operation);
        Thread.ofVirtual().name("veto-mcp-io").start(task);
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("MCP server timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MCP request interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("MCP transport failed", cause);
        } finally {
            task.cancel(true);
        }
    }

    private @NonNull JsonNode invokeSse(
            McpTransport.@NonNull SseMcpTransport transport, @NonNull String body, long timeoutMs)
            throws IOException {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build()) {
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
                throw new IOException("SSE MCP server returned " + response.statusCode());
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
            return parseResponse(responseReader.readTree(data.toString()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("SSE MCP request interrupted", e);
        }
    }

    private @NonNull JsonNode readResponse(@NonNull InputStream stream) throws IOException {
        try (var reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node = responseReader.readTree(line);
                // Notifications can precede the response on the same stream.
                if (node != null
                        && node.isObject()
                        && !node.has("id")
                        && node.path("method").isTextual()
                        && "2.0".equals(node.path("jsonrpc").asText())) continue;
                return parseResponse(node);
            }
        }
        throw new IOException("MCP server returned no response");
    }

    private @NonNull JsonNode parseResponse(JsonNode node) throws IOException {
        if (node == null
                || !node.isObject()
                || !"2.0".equals(node.path("jsonrpc").asText())
                || !node.path("id").isIntegralNumber()
                || !node.path("id").canConvertToInt()
                || node.path("id").intValue() != 1
                || node.has("result") == node.has("error")) {
            throw new IOException("Invalid MCP JSON-RPC response envelope");
        }
        JsonNode error = node.get("error");
        if (error != null) {
            // Remote error data may contain credentials or sensitive request values.
            throw new IOException("MCP server returned a JSON-RPC error");
        }
        JsonNode result = node.get("result");
        if (result == null) {
            throw new IOException("MCP server response missing 'result'");
        }
        return result;
    }
}
