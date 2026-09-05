package top.focess.veto.agent.capability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.memory.MemoryQuery;
import top.focess.veto.memory.MemoryStore;
import top.focess.veto.memory.MemoryTier;

@Component
public final class MemoryReadCapabilityImpl implements MemoryReadCapability {
    private final @NonNull MemoryStore store;

    public MemoryReadCapabilityImpl(@NonNull MemoryStore store) {
        this.store = store;
    }

    @Override
    public @NonNull List<MemoryStore.ScoredMemory> search(
            @NonNull String query, int limit, float scoreFloor) {
        ToolCallContext ctx = CapabilityAccess.require(ToolCapability.MEMORY_READ, "recall_memory");
        UUID sessionId = ctx.sessionId();
        if (sessionId == null) {
            try {
                sessionId = UUID.fromString(ctx.agentId());
            } catch (IllegalArgumentException e) {
                return ToolErrors.failure("no session context; memories not recalled");
            }
        }
        MemoryQuery sessionQuery =
                new MemoryQuery(
                        query,
                        List.of(MemoryTier.SESSION),
                        sessionId,
                        null,
                        ctx.userId(),
                        limit,
                        scoreFloor);
        MemoryQuery crossSessionQuery =
                new MemoryQuery(
                        query,
                        List.of(MemoryTier.CROSS_SESSION),
                        null,
                        null,
                        ctx.userId(),
                        limit,
                        scoreFloor);
        List<MemoryStore.ScoredMemory> matches = new ArrayList<>(limit * 2);
        matches.addAll(store.search(sessionQuery));
        matches.addAll(store.search(crossSessionQuery));
        matches.sort(Comparator.comparingDouble(MemoryStore.ScoredMemory::score).reversed());
        if (matches.size() > limit) {
            matches = new ArrayList<>(matches.subList(0, limit));
        }
        return matches;
    }
}
