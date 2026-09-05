package top.focess.veto.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.agent.mcp.transport.McpJsonRpcClient;
import top.focess.veto.agent.mcp.transport.McpTransport;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.util.Nullness;

/** Tests for remote MCP tool discovery and invocation over JSON-RPC transports. */
class McpJsonRpcClientTest {
    private final @NonNull List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void closeServers() {
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void httpTransportDoesNotRenderCredentials() {
        var transport =
                new McpTransport.SseMcpTransport(
                        "https://user:password@example.com/mcp?key=private", "secret-bearer-token");
        assertEquals("SseMcpTransport[credentials redacted]", transport.toString());
        assertEquals("secret-bearer-token", transport.authToken());
    }

    @Test
    void unsupportedStdioNeverStartsTheProcess() throws Exception {
        ProcessBuilder builder = mock(ToolDocs.nonNullClass(ProcessBuilder.class));
        IOException error =
                assertThrows(
                        IOException.class,
                        () ->
                                new McpJsonRpcClient()
                                        .discoverTools(
                                                new McpTransport.StdioMcpTransport(builder)));
        assertTrue(
                Nullness.requireNonNull(error.getMessage())
                        .contains("until sandboxed process execution is integrated"));
        verifyNoInteractions(builder);
    }

    @Test
    void unsupportedSocketNeverConnects(@TempDir @NonNull Path root) throws Exception {
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            Path socket = root.resolve("rpc.sock");
            server.bind(UnixDomainSocketAddress.of(socket));
            server.configureBlocking(false);
            IOException error =
                    assertThrows(
                            IOException.class,
                            () ->
                                    new McpJsonRpcClient()
                                            .discoverTools(
                                                    new McpTransport.SocketMcpTransport(socket)));
            assertTrue(
                    Nullness.requireNonNull(error.getMessage())
                            .contains("until restricted socket access is integrated"));
            assertNull(server.accept());
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("Unix domain sockets are unavailable");
        }
    }

    @Test
    void parseToolsListFromJsonRpcResponse() throws Exception {
        String response =
                """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "result": {
                    "tools": [
                      {
                        "name": "remote_search",
                        "description": "Search the web",
                        "inputSchema": {
                          "type": "object",
                          "properties": {"q": {"type": "string"}}
                        }
                      }
                    ]
                  }
                }
                """;
        // Stdio frames each JSON-RPC message on a single line.
        var transport = sse(response.replace("\n", ""));
        var tools = new McpJsonRpcClient().discoverTools(transport);
        assertTrue(tools.getFirst().serverName().startsWith("mcp-"));
        assertFalse(tools.getFirst().serverName().contains("secret-password"));
        assertEquals(1, tools.size());
        assertEquals("remote_search", tools.getFirst().name());
        assertEquals("Search the web", tools.getFirst().description());
    }

    @Test
    void skipsNotificationsAndPreservesUnicodeArgumentsAndResults() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        var transport =
                server(
                        exchange -> {
                            requestBody.set(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
                            byte[] bytes =
                                    ("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\"}\n\n"
                                                    + "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"text\":\"你好\"}}\n\n")
                                            .getBytes(StandardCharsets.UTF_8);
                            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                            exchange.sendResponseHeaders(200, bytes.length);
                            try (var out = exchange.getResponseBody()) {
                                out.write(bytes);
                            }
                        });
        JsonNode result = new McpJsonRpcClient().callTool(transport, "echo", Map.of("text", "你好"));
        assertEquals("你好", result.path("text").asText());
        JsonNode request = new ObjectMapper().readTree(requestBody.get());
        assertEquals("tools/call", request.path("method").asText());
        assertEquals("你好", request.path("params").path("arguments").path("text").asText());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "[]",
                "{}",
                "{\"jsonrpc\":\"1.0\",\"id\":1,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{},\"error\":{}}"
            })
    void rejectsInvalidResponseEnvelopes(@NonNull String response) throws Exception {
        var transport = sse(response);
        IOException failure =
                assertThrows(
                        IOException.class, () -> new McpJsonRpcClient().discoverTools(transport));
        assertEquals("Invalid MCP JSON-RPC response envelope", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}} {}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"id\":1,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[],\"tools\":[]}}"
            })
    void rejectsTrailingJsonAndDuplicateKeys(@NonNull String response) throws Exception {
        var transport = sse(response);
        assertThrows(IOException.class, () -> new McpJsonRpcClient().discoverTools(transport));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"tools\":{}}",
                "{\"tools\":[{}]}",
                "{\"tools\":[{\"name\":42,\"inputSchema\":{\"type\":\"object\"}}]}",
                "{\"tools\":[{\"name\":\"x\",\"inputSchema\":[]}]}",
                "{\"tools\":[{\"name\":\"x\",\"inputSchema\":{\"type\":\"string\"}}]}"
            })
    void rejectsMalformedDiscoveredTools(@NonNull String result) throws Exception {
        var transport = sse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}");
        assertThrows(IOException.class, () -> new McpJsonRpcClient().discoverTools(transport));
    }

    @Test
    void doesNotExposeRemoteErrorPayload() throws Exception {
        var transport =
                sse("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"message\":\"secret-token\"}}");
        IOException failure =
                assertThrows(
                        IOException.class, () -> new McpJsonRpcClient().discoverTools(transport));
        assertEquals("MCP server returned a JSON-RPC error", failure.getMessage());
    }

    private McpTransport.@NonNull SseMcpTransport sse(@NonNull String response) throws IOException {
        return server(
                exchange -> {
                    byte[] bytes = ("data: " + response + "\n\n").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
    }

    private McpTransport.@NonNull SseMcpTransport server(@NonNull HttpHandler handler)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", handler);
        server.setExecutor(command -> Thread.ofVirtual().start(command));
        server.start();
        servers.add(server);
        return new McpTransport.SseMcpTransport(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", "");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "file:///tmp/mcp",
                "/mcp",
                "https://user:secret@example.com/mcp",
                "https://example.com/mcp#token"
            })
    void rejectsInvalidEndpoints(@NonNull String url) {
        IOException error =
                assertThrows(
                        IOException.class,
                        () ->
                                new McpJsonRpcClient()
                                        .discoverTools(new McpTransport.SseMcpTransport(url, "")));
        assertTrue(Nullness.requireNonNull(error.getMessage()).contains("absolute HTTP or HTTPS"));
        assertFalse(Nullness.requireNonNull(error.getMessage()).contains("secret"));
    }

    @Test
    void redirectsAreNeverFollowed() throws Exception {
        AtomicInteger targetCalls = new AtomicInteger();
        var target =
                server(
                        exchange -> {
                            targetCalls.incrementAndGet();
                            exchange.sendResponseHeaders(200, -1);
                            exchange.close();
                        });
        var source =
                server(
                        exchange -> {
                            exchange.getResponseHeaders().set("Location", target.baseUrl());
                            exchange.sendResponseHeaders(302, -1);
                            exchange.close();
                        });
        IOException error =
                assertThrows(IOException.class, () -> new McpJsonRpcClient().discoverTools(source));
        assertTrue(Nullness.requireNonNull(error.getMessage()).contains("302"));
        assertEquals(0, targetCalls.get());
    }

    @Test
    void boundsResponseBytesBeforeJsonParsing() throws Exception {
        var transport = sse("x".repeat(4 * 1024 * 1024 + 1));
        IOException error =
                assertThrows(
                        IOException.class, () -> new McpJsonRpcClient().discoverTools(transport));
        assertTrue(Nullness.requireNonNull(error.getMessage()).contains("exceeds 4 MiB"));
    }

    @Test
    void boundsNotificationFloods() throws Exception {
        var transport =
                server(
                        exchange -> {
                            byte[] bytes =
                                    "data: {\"jsonrpc\":\"2.0\",\"method\":\"notice\"}\n\n"
                                            .repeat(129)
                                            .getBytes(StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(200, bytes.length);
                            try (var out = exchange.getResponseBody()) {
                                out.write(bytes);
                            }
                        });
        IOException error =
                assertThrows(
                        IOException.class, () -> new McpJsonRpcClient().discoverTools(transport));
        assertTrue(Nullness.requireNonNull(error.getMessage()).contains("notification limit"));
    }

    @Test
    void deadlineIncludesPartialResponseBody() throws Exception {
        var transport =
                server(
                        exchange -> {
                            exchange.sendResponseHeaders(200, 0);
                            try (var out = exchange.getResponseBody()) {
                                out.write("data: {".getBytes(StandardCharsets.UTF_8));
                                out.flush();
                                try {
                                    Thread.sleep(2000);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        });
        var rpc =
                new McpJsonRpcClient(
                        new ObjectMapper(), Duration.ofMillis(100), Duration.ofMillis(100));
        IOException error =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(3),
                        () -> assertThrows(IOException.class, () -> rpc.discoverTools(transport)));
        assertEquals("MCP server timed out", error.getMessage());
    }

    @Test
    void allTransportSealedVariantsAreConstructable() {
        // The four transport variants are sealed; we can construct each and pattern-match.
        McpTransport sse = new McpTransport.SseMcpTransport("https://example.com/mcp", "token");
        McpTransport stdio = new McpTransport.StdioMcpTransport(new ProcessBuilder("echo", "hi"));
        McpTransport socket = new McpTransport.SocketMcpTransport(Path.of("/tmp/sock"));
        McpTransport client = new McpTransport.ClientDelegatedMcpTransport("ws-channel-1");
        assertNotNull(sse);
        assertNotNull(stdio);
        assertNotNull(socket);
        assertNotNull(client);
    }

    @Test
    void missingSocketFailsWithIOException() {
        McpJsonRpcClient rpc = new McpJsonRpcClient();
        McpTransport socket = new McpTransport.SocketMcpTransport(Path.of("/tmp/sock"));
        assertThrows(IOException.class, () -> rpc.discoverTools(socket));
    }
}
