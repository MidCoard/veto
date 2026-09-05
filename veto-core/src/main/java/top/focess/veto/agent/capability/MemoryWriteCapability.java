package top.focess.veto.agent.capability;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.memory.MemoryId;

public sealed interface MemoryWriteCapability extends Capability permits MemoryWriteCapabilityImpl {
    @NonNull MemoryId add(@NonNull String content, UUID projectId);

    MemoryId promote(@NonNull MemoryId id);

    boolean forget(@NonNull MemoryId id);
}
