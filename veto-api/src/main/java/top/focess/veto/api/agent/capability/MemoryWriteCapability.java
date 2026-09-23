package top.focess.veto.api.agent.capability;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.memory.MemoryId;

public interface MemoryWriteCapability extends Capability {
    @NonNull MemoryId add(@NonNull String content, UUID projectId);

    MemoryId promote(@NonNull MemoryId id);

    boolean forget(@NonNull MemoryId id);
}
