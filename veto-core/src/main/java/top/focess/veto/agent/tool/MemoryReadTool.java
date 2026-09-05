package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.MemoryReadCapability;

public non-sealed interface MemoryReadTool<T> extends AgentTool<T> {
    @NonNull MemoryReadCapability memoryReadCapability();

    @NonNull String execute(@NonNull T args, @NonNull MemoryReadCapability capability);

    @Override
    default @NonNull ToolCapability getCapability() {
        return ToolCapability.MEMORY_READ;
    }

    @Override
    default @NonNull String execute(@NonNull T args) {
        return execute(args, memoryReadCapability());
    }
}
