package top.focess.veto.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.agent.mcp.transport.McpJsonRpcClient;
import top.focess.veto.agent.mcp.transport.McpTransport;
import top.focess.veto.agent.tool.ToolDocs;

/** Tests for remote MCP tool discovery and invocation over JSON-RPC transports. */
class McpJsonRpcClientTest {

    @Test
    void httpTransportDoesNotRenderCredentials() {
        var transport =
                new McpTransport.SseMcpTransport(
                        "https://user:password@example.com/mcp?key=private", "secret-bearer-token");
        assertEquals("SseMcpTransport[credentials redacted]", transport.toString());
        assertEquals("secret-bearer-token", transport.authToken());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stdioTimeoutClosesSilentOrPartialResponses(boolean partial) throws Exception {
        PipedOutputStream server = new PipedOutputStream();
        try (PipedInputStream response = new PipedInputStream(server)) {
            Process process = mock(ToolDocs.nonNullClass(Process.class));
            ProcessBuilder builder = mock(ToolDocs.nonNullClass(ProcessBuilder.class));
            when(builder.start()).thenReturn(process);
            when(process.getInputStream()).thenReturn(response);
            when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
            doAnswer(
                            invocation -> {
                                server.close();
                                return null;
                            })
                    .when(process)
                    .destroy();
            if (partial) server.write('{');
            var rpc =
                    new McpJsonRpcClient(
                            new ObjectMapper(), Duration.ofMillis(100), Duration.ofMillis(100));
            IOException failure =
                    assertTimeoutPreemptively(
                            Duration.ofSeconds(3),
                            () ->
                                    assertThrows(
                                            IOException.class,
                                            () ->
                                                    rpc.discoverTools(
                                                            new McpTransport.StdioMcpTransport(
                                                                    builder))));
            assertEquals("MCP server timed out", failure.getMessage());
            verify(process).destroy();
        } finally {
            server.close();
        }
    }

    @Test
    void socketTimeoutClosesAnUnresponsiveConnection(@TempDir @NonNull Path root) throws Exception {
        java.nio.channels.ServerSocketChannel listener;
        try {
            listener =
                    java.nio.channels.ServerSocketChannel.open(
                            java.net.StandardProtocolFamily.UNIX);
        } catch (UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("Unix domain sockets are unavailable");
            return;
        }
        try (var server = listener) {
            var address = root.resolve("rpc.sock");
            server.bind(java.net.UnixDomainSocketAddress.of(address));
            var closed = new java.util.concurrent.CompletableFuture<Boolean>();
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try (var peer = server.accept()) {
                                    var buffer = java.nio.ByteBuffer.allocate(4096);
                                    while (peer.read(buffer) >= 0) buffer.clear();
                                    closed.complete(true);
                                } catch (IOException e) {
                                    closed.completeExceptionally(e);
                                }
                            });
            var rpc =
                    new McpJsonRpcClient(
                            new ObjectMapper(), Duration.ofMillis(200), Duration.ofMillis(200));
            IOException failure =
                    assertTimeoutPreemptively(
                            Duration.ofSeconds(3),
                            () ->
                                    assertThrows(
                                            IOException.class,
                                            () ->
                                                    rpc.discoverTools(
                                                            new McpTransport.SocketMcpTransport(
                                                                    address))));
            assertEquals("MCP server timed out", failure.getMessage());
            assertTrue(closed.get(2, java.util.concurrent.TimeUnit.SECONDS));
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
        var tools = new McpJsonRpcClient().discoverTools(stdio(response.replace("\n", "")));
        assertEquals(1, tools.size());
        assertEquals("remote_search", tools.getFirst().name());
        assertEquals("Search the web", tools.getFirst().description());
    }

    @Test
    void skipsNotificationsAndPreservesUnicodeArgumentsAndResults() throws Exception {
        var output = new ByteArrayOutputStream();
        Process process = mock(ToolDocs.nonNullClass(Process.class));
        ProcessBuilder builder = mock(ToolDocs.nonNullClass(ProcessBuilder.class));
        when(builder.start()).thenReturn(process);
        when(process.getOutputStream()).thenReturn(output);
        when(process.getInputStream())
                .thenReturn(
                        new ByteArrayInputStream(
                                ("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{}}\n"
                                                + "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"text\":\"你好\"}}\n")
                                        .getBytes(StandardCharsets.UTF_8)));
        JsonNode result =
                new McpJsonRpcClient()
                        .callTool(
                                new McpTransport.StdioMcpTransport(builder),
                                "echo",
                                Map.of("text", "你好"));
        assertEquals("你好", result.path("text").asText());
        JsonNode request = new ObjectMapper().readTree(output.toString(StandardCharsets.UTF_8));
        assertEquals("tools/call", request.path("method").asText());
        assertEquals("你好", request.path("params").path("arguments").path("text").asText());
        verify(process).destroy();
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
        var transport = stdio(response);
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
        var transport = stdio(response);
        assertThrows(IOException.class, () -> new McpJsonRpcClient().discoverTools(transport));
    }

    @Test
    void doesNotExposeRemoteErrorPayload() throws Exception {
        var transport =
                stdio("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"message\":\"secret-token\"}}");
        IOException failure =
                assertThrows(
                        IOException.class, () -> new McpJsonRpcClient().discoverTools(transport));
        assertEquals("MCP server returned a JSON-RPC error", failure.getMessage());
    }

    private static McpTransport.@NonNull StdioMcpTransport stdio(@NonNull String response)
            throws IOException {
        Process process = mock(ToolDocs.nonNullClass(Process.class));
        ProcessBuilder builder = mock(ToolDocs.nonNullClass(ProcessBuilder.class));
        when(builder.start()).thenReturn(process);
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(process.getInputStream())
                .thenReturn(
                        new ByteArrayInputStream(
                                (response + "\n").getBytes(StandardCharsets.UTF_8)));
        return new McpTransport.StdioMcpTransport(builder);
    }

    @Test
    void allTransportSealedVariantsAreConstructable() {
        // The four transport variants are sealed; we can construct each and pattern-match.
        McpTransport sse = new McpTransport.SseMcpTransport("https://example.com/mcp", "token");
        McpTransport stdio = new McpTransport.StdioMcpTransport(new ProcessBuilder("echo", "hi"));
        McpTransport socket =
                new McpTransport.SocketMcpTransport(java.nio.file.Path.of("/tmp/sock"));
        McpTransport client = new McpTransport.ClientDelegatedMcpTransport("ws-channel-1");
        assertNotNull(sse);
        assertNotNull(stdio);
        assertNotNull(socket);
        assertNotNull(client);
    }

    @Test
    void missingSocketFailsWithIOException() {
        McpJsonRpcClient rpc = new McpJsonRpcClient();
        McpTransport socket =
                new McpTransport.SocketMcpTransport(java.nio.file.Path.of("/tmp/sock"));
        assertThrows(IOException.class, () -> rpc.discoverTools(socket));
    }
}
