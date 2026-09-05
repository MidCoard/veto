package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.capability.RemoteCallCapabilityImpl;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.mcp.transport.McpJsonRpcClient;
import top.focess.veto.agent.mcp.transport.McpTransport.SseMcpTransport;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.ToolResultPresentationMode;

class RemoteCapabilityBoundaryTest {
    private final @NonNull ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void clear() {
        ToolCallContextHolder.clear();
    }

    @Test
    void onlyExactRegisteredRemoteCallCanReachTransport(@TempDir @NonNull Path root)
            throws Exception {
        var schema = mapper.createObjectNode().put("type", "object");
        var definition = new RemoteToolDefinition("lookup", "Lookup", "server-one", schema);
        var otherServer = new RemoteToolDefinition("lookup", "Lookup", "server-two", schema);
        var transport = new SseMcpTransport("https://example.invalid/mcp", "");
        var client = mock(ToolDocs.nonNullClass(McpJsonRpcClient.class));
        var capability = new RemoteCallCapabilityImpl(definition, transport, client);
        var otherCapability = new RemoteCallCapabilityImpl(otherServer, transport, client);
        var call = new ToolCall("lookup", Map.of("id", "allowed"), "approved-call");
        UUID user = UUID.randomUUID();
        var permit =
                ToolExecutionPermit.capture(call, definition, Workspace.single(root, PathMode.REAL))
                        .withCaller("agent", user, null, "owner", null);
        assertEquals("server-one", permit.remoteServerName());
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent",
                        user,
                        null,
                        "owner",
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        permit));
        ToolCallContextHolder.setCurrentCallId(call.callId());
        assertThrows(SecurityException.class, () -> otherCapability.call(call));
        assertThrows(
                SecurityException.class,
                () ->
                        capability.call(
                                new ToolCall("lookup", Map.of("id", "other"), call.callId())));
        assertThrows(
                SecurityException.class,
                () -> capability.call(new ToolCall("delete", call.args(), call.callId())));
        ToolCallContextHolder.setCurrentCallId("another-call");
        assertThrows(SecurityException.class, () -> capability.call(call));
        ToolCallContextHolder.setCurrentCallId(call.callId());
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "other-agent",
                        user,
                        null,
                        "owner",
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        permit));
        assertThrows(SecurityException.class, () -> capability.call(call));
        verifyNoInteractions(client);
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent",
                        user,
                        null,
                        "owner",
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        permit));
        var result = mapper.createObjectNode().put("isError", false);
        when(client.callTool(transport, "lookup", call.args())).thenReturn(result);
        assertSame(result, capability.call(call));
        verify(client).callTool(transport, "lookup", call.args());
        ToolCallContextHolder.clear();
        assertThrows(SecurityException.class, () -> capability.call(call));
        verifyNoMoreInteractions(client);
    }

    @Test
    void remoteSchemaCannotBeMutatedAfterRegistration() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        var definition = new RemoteToolDefinition("lookup", "Lookup", "server", schema);
        schema.put("type", "string");
        ((ObjectNode) definition.inputSchema()).put("type", "array");
        assertEquals("object", definition.inputSchema().path("type").asText());
    }
}
