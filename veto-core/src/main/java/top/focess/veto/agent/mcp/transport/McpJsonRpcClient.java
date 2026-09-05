package top.focess.veto.agent.mcp.transport;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.agent.tool.RemoteToolDefinition;

/**
 * A minimal JSON-RPC 2.0 client for talking to remote MCP servers. Supports the {@code tools/list}
 * discovery and {@code tools/call} invocation over a fixed HTTP endpoint with JSON or SSE
 * responses. Local process and socket transports are refused until their restricted execution is
 * integrated.
 *
 * <p>The client intentionally implements only the MCP methods Veto needs ({@code tools/list} and
 * {@code tools/call}); it does not implement notifications, sampling, roots, or other optional MCP
 * capabilities.
 */
public final class McpJsonRpcClient {

    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_NOTIFICATION_EVENTS = 128;

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
            case McpTransport.StdioMcpTransport ignored ->
                    throw new IOException(
                            "MCP stdio transport is unavailable until sandboxed process execution is integrated");
            case McpTransport.SseMcpTransport sse -> invokeSse(sse, body, timeoutMs);
            case McpTransport.SocketMcpTransport ignored ->
                    throw new IOException(
                            "MCP socket transport is unavailable until restricted socket access is integrated");
            case McpTransport.ClientDelegatedMcpTransport delegated ->
                    throw new IOException(
                            "Client-delegated transport is a UI-channel transport, not a JSON-RPC transport: "
                                    + delegated);
        };
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
            if (cause instanceof HttpTimeoutException)
                throw new IOException("MCP server timed out", cause);
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
        URI endpoint;
        try {
            endpoint = URI.create(transport.baseUrl());
        } catch (IllegalArgumentException e) {
            throw new IOException("MCP endpoint must be an absolute HTTP or HTTPS URL");
        }
        if (!("http".equalsIgnoreCase(endpoint.getScheme())
                        || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null) {
            throw new IOException(
                    "MCP endpoint must be an absolute HTTP or HTTPS URL without user information or a fragment");
        }
        HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(timeoutMs))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        AtomicReference<@Nullable InputStream> activeBody = new AtomicReference<>();
        try {
            HttpRequest.Builder requestBuilder =
                    HttpRequest.newBuilder(endpoint)
                            .timeout(Duration.ofMillis(timeoutMs))
                            .header("Content-Type", "application/json")
                            .header("Accept", "text/event-stream, application/json");
            String authToken = transport.authToken();
            if (!authToken.isBlank()) requestBuilder.header("Authorization", "Bearer " + authToken);
            HttpRequest request =
                    requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return withDeadline(
                    () -> {
                        HttpResponse<InputStream> response =
                                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                        try (InputStream stream = new BufferedInputStream(response.body())) {
                            activeBody.set(stream);
                            if (response.statusCode() != 200)
                                throw new IOException(
                                        "HTTP MCP server returned " + response.statusCode());
                            String contentType =
                                    response.headers().firstValue("Content-Type").orElse("");
                            if (contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
                                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                                if (bytes.length > MAX_RESPONSE_BYTES)
                                    throw new IOException("MCP response exceeds 4 MiB");
                                return parseResponse(
                                        responseReader.readTree(
                                                new String(bytes, StandardCharsets.UTF_8)));
                            }
                            return readEvents(stream);
                        } finally {
                            activeBody.set(null);
                        }
                    },
                    timeoutMs);
        } finally {
            InputStream stream = activeBody.getAndSet(null);
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
            client.shutdownNow();
        }
    }

    private @NonNull JsonNode readEvents(@NonNull InputStream stream) throws IOException {
        StringBuilder data = new StringBuilder();
        ByteArrayOutputStream lineBytes = new ByteArrayOutputStream();
        int notifications = 0;
        int consumed = 0;
        while (true) {
            int next = stream.read();
            if (next >= 0 && ++consumed > MAX_RESPONSE_BYTES)
                throw new IOException("MCP response exceeds 4 MiB");
            if (next >= 0 && next != '\n') {
                lineBytes.write(next);
                continue;
            }
            String line = lineBytes.toString(StandardCharsets.UTF_8).stripTrailing();
            lineBytes.reset();
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(5).strip());
            }
            if ((line.isBlank() || next < 0) && !data.isEmpty()) {
                JsonNode event = responseReader.readTree(data.toString());
                data.setLength(0);
                if (event != null
                        && event.isObject()
                        && !event.has("id")
                        && event.path("method").isTextual()
                        && "2.0".equals(event.path("jsonrpc").asText())) {
                    if (++notifications > MAX_NOTIFICATION_EVENTS)
                        throw new IOException("MCP notification limit exceeded");
                } else {
                    return parseResponse(event);
                }
            }
            if (next < 0) throw new IOException("MCP server returned no response");
        }
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
