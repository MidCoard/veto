package top.focess.veto.builtin.memory;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** A unique identifier for a {@link Memory} entry. */
public record MemoryId(@NonNull UUID value) {

    /** Creates a fresh random identifier. */
    public static @NonNull MemoryId random() {
        return new MemoryId(UUID.randomUUID());
    }

    @Override
    public @NonNull String toString() {
        return "MemoryId{" + value + "}";
    }
}
