package top.focess.veto.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** Tests for remote MCP tool discovery and invocation over JSON-RPC transports. */
class McpJsonRpcClientTest {

    @Test
    void parseToolsListFromJsonRpcResponse() throws Exception {
        // Simulate a server response by parsing JSON.
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
        JsonNode result = new ObjectMapper().readTree(response).get("result");
        JsonNode tools = result.get("tools");
        assertEquals(1, tools.size());
        assertEquals("remote_search", tools.get(0).get("name").asText());
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
    void socketTransportExecutionNotYetImplemented() {
        McpJsonRpcClient rpc = new McpJsonRpcClient();
        McpTransport socket =
                new McpTransport.SocketMcpTransport(java.nio.file.Path.of("/tmp/sock"));
        assertThrows(IOException.class, () -> rpc.discoverTools(socket));
    }
}
