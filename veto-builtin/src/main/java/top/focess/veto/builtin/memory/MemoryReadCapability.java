package top.focess.veto.builtin.memory;

import java.util.List;
import org.jspecify.annotations.NonNull;

public interface MemoryReadCapability {
    @NonNull List<ScoredMemory> search(
            @NonNull String query, @NonNull MemoryTier tier, int limit, float scoreFloor);
}
