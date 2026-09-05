package top.focess.veto.agent.capability;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.memory.MemoryStore;

public sealed interface MemoryReadCapability extends Capability permits MemoryReadCapabilityImpl {
    @NonNull List<MemoryStore.ScoredMemory> search(
            @NonNull String query, int limit, float scoreFloor);
}
