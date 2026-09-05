package top.focess.veto.agent.capability;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.mcp.transport.McpJsonRpcClient;
import top.focess.veto.agent.mcp.transport.McpTransport.SseMcpTransport;
import top.focess.veto.agent.tool.RemoteToolDefinition;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.llm.core.ToolCall;

/** A registered endpoint cannot be supplied or changed by a tool call. */
public final class RemoteCallCapabilityImpl implements RemoteCallCapability {
    private final @NonNull RemoteToolDefinition definition;
    private final @NonNull SseMcpTransport transport;
    private final @NonNull McpJsonRpcClient client;

    public RemoteCallCapabilityImpl(
            @NonNull RemoteToolDefinition definition,
            @NonNull SseMcpTransport transport,
            @NonNull McpJsonRpcClient client) {
        this.definition = definition;
        this.transport = transport;
        this.client = client;
    }

    @Override
    public @NonNull JsonNode call(@NonNull ToolCall call) throws IOException {
        var context = CapabilityAccess.require(ToolCapability.REMOTE_UNKNOWN);
        var permit = context.executionPermit();
        if (!definition.name().equals(call.toolName())
                || !permit.authorizes(call, definition, context)) {
            throw new SecurityException(
                    "This remote tool call is not authorized; submit a fresh call.");
        }
        return client.callTool(transport, definition.name(), call.args());
    }
}
