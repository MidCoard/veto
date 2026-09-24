package top.focess.veto.builtin.memory;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.ToolCapability;

public interface MemoryReadTool<T> extends AgentTool<T> {

    @NonNull MemoryReadCapability memoryReadCapability();

    @NonNull String execute(@NonNull T args, @NonNull MemoryReadCapability capability);

    @Override
    default @NonNull ToolCapability getCapability() {
        return ToolCapability.PLUGIN_LOCAL;
    }

    @Override
    default @NonNull String execute(@NonNull T args) {
        return execute(args, memoryReadCapability());
    }
}
