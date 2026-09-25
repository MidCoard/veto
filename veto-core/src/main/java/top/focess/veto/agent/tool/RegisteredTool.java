package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.RemoteCallCapability;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.plugin.contract.Tool;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/** Host-only adapters: bind each definition to its exact execution implementation. */
sealed interface RegisteredTool {
    /** The tool definition this registration binds to its execution implementation. */
    @NonNull ToolDefinition definition();

    /** An out-of-process script plugin tool, executed through the plugin runtime. */
    record Plugin(
            @NonNull RemoteToolDefinition definition,
            @NonNull Tool descriptor,
            @NonNull ManagedPlugin runtime)
            implements RegisteredTool {}

    /** One execution binding for every record-authored tool, irrespective of origin. */
    record Local(
            @NonNull LocalToolDefinition definition,
            @NonNull CapabilityTool<?> handler,
            ManagedPlugin runtime)
            implements RegisteredTool {}

    /** An external MCP tool, executed through its bound remote-call capability. */
    record Remote(
            @NonNull RemoteToolDefinition definition, @NonNull RemoteCallCapability capability)
            implements RegisteredTool {}
}
