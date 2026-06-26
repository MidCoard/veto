package top.focess.veto.memory;

import java.util.UUID;

/** A unique identifier for a {@link Memory} entry. */
public record MemoryId(UUID value) {

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
