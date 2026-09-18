package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.RemoteCallCapability;

/** Host-only adapters: bind each definition to its exact execution implementation. */
sealed interface RegisteredTool {
    @NonNull ToolDefinition definition();

    record Native(@NonNull NativeToolDefinition definition, @NonNull NativeTool<?> handler)
            implements RegisteredTool {}

    record Agent(@NonNull AgentToolDefinition definition, @NonNull AgentTool<?> handler)
            implements RegisteredTool {}

    record Remote(
            @NonNull RemoteToolDefinition definition, @NonNull RemoteCallCapability capability)
            implements RegisteredTool {}
}
