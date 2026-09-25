package top.focess.veto.builtin.memory;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Host capability for adding, promoting and forgetting memories. */
public interface MemoryWriteCapability {
    /** Stores new content and returns its identifier. */
    @NonNull MemoryId add(@NonNull String content, UUID projectId);

    /**
     * Promotes a memory to a longer-lived tier; returns the surviving id, or {@code null} if
     * unknown.
     */
    MemoryId promote(@NonNull MemoryId id);

    /** Deletes the memory; returns whether an entry was removed. */
    boolean forget(@NonNull MemoryId id);
}
