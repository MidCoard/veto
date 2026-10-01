package top.focess.veto.builtin.memory;

import java.util.List;
import org.jspecify.annotations.NonNull;

/** Builtin memory operations bound to a host-authorized invocation. */
public interface MemoryReadCapability {
    /** Searches memories of the given tier, returning matches above the score floor. */
    @NonNull List<ScoredMemory> search(
            @NonNull String query, @NonNull MemoryTier tier, int limit, float scoreFloor);
}
