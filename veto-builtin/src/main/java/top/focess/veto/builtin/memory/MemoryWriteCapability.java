package top.focess.veto.builtin.memory;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface MemoryWriteCapability {
    @NonNull MemoryId add(@NonNull String content, UUID projectId);

    MemoryId promote(@NonNull MemoryId id);

    boolean forget(@NonNull MemoryId id);
}
