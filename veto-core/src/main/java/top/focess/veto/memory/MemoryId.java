package top.focess.veto.memory;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** A unique identifier for a {@link Memory} entry. */
public record MemoryId(@NonNull UUID value) {

    public MemoryId {
        if (value == null) {
            throw new IllegalArgumentException("value");
        }
    }

    public static MemoryId random() {
        return new MemoryId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return "MemoryId{" + value + "}";
    }
}
