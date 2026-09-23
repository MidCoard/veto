package top.focess.veto.api.agent.capability;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.memory.MemoryTier;
import top.focess.veto.api.memory.ScoredMemory;

public interface MemoryReadCapability extends Capability {
    @NonNull List<ScoredMemory> search(
            @NonNull String query, @NonNull MemoryTier tier, int limit, float scoreFloor);
}
