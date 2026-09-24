package top.focess.veto.builtin.memory;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.builtin.memory.embedder.Embedder;

/** Trusted distribution adapter for builtin's optional durable backends; never a host API. */
@FunctionalInterface
public interface MemoryBackendFactory {
    @NonNull MemoryStore open(@NonNull String profile, @NonNull Embedder embedder);

    /** Removes data from every existing durable backend, including previously selected profiles. */
    default void deleteOwner(@NonNull UUID userId) {
        throw new IllegalStateException("Durable owner cleanup is unavailable");
    }

    default void deleteSession(@NonNull UUID userId, @NonNull UUID sessionId) {
        throw new IllegalStateException("Durable session cleanup is unavailable");
    }
}
