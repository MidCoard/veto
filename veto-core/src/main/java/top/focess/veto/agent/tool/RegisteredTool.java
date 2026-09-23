package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.RemoteCallCapability;
import top.focess.veto.plugin.contract.Tool;
import top.focess.veto.plugin.runtime.ManagedPlugin;

/** Host-only adapters: bind each definition to its exact execution implementation. */
sealed interface RegisteredTool {
    @NonNull ToolDefinition definition();

    /** An out-of-process script plugin tool, executed through the plugin runtime. */
    record Plugin(
            @NonNull RemoteToolDefinition definition,
            @NonNull Tool descriptor,
            @NonNull ManagedPlugin runtime)
            implements RegisteredTool {}

    /** An in-process JAR plugin tool, executed through the internal tool state. */
    record Capability(
            @NonNull NativeToolDefinition definition,
            @NonNull CapabilityTool<?> handler,
            @NonNull ManagedPlugin runtime)
            implements RegisteredTool {}

    record Native(@NonNull NativeToolDefinition definition, @NonNull NativeTool<?> handler)
            implements RegisteredTool {}

    record Agent(@NonNull AgentToolDefinition definition, @NonNull AgentTool<?> handler)
            implements RegisteredTool {}

    record Remote(
            @NonNull RemoteToolDefinition definition, @NonNull RemoteCallCapability capability)
            implements RegisteredTool {}
}
