package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.MemoryReadCapability;

public interface MemoryReadTool<T>
        extends AgentTool<T>, HostCapabilityTool<T, MemoryReadCapability> {
    default @NonNull Class<MemoryReadCapability> capabilityType() {
        return ToolDocs.nonNullClass(MemoryReadCapability.class);
    }

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
