package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.MemoryWriteCapability;

public interface MemoryWriteTool<T>
        extends AgentTool<T>, HostCapabilityTool<T, MemoryWriteCapability> {
    default @NonNull Class<MemoryWriteCapability> capabilityType() {
        return ToolDocs.nonNullClass(MemoryWriteCapability.class);
    }

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
