package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.MemoryWriteCapability;

public non-sealed interface MemoryWriteTool<T> extends AgentTool<T> {
    @NonNull MemoryWriteCapability memoryWriteCapability();

    @NonNull String execute(@NonNull T args, @NonNull MemoryWriteCapability capability);

    @Override
    default @NonNull ToolCapability getCapability() {
        return ToolCapability.MEMORY_WRITE;
    }

    @Override
    default @NonNull String execute(@NonNull T args) {
        return execute(args, memoryWriteCapability());
    }
}
