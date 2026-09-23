package top.focess.veto.agent.capability;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.api.agent.capability.MemoryReadCapability;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.memory.MemoryTier;
import top.focess.veto.api.memory.ScoredMemory;
import top.focess.veto.memory.MemoryQuery;
import top.focess.veto.memory.MemoryStore;

@Component
public final class MemoryReadCapabilityImpl implements MemoryReadCapability {
    private final @NonNull MemoryStore store;

    public MemoryReadCapabilityImpl(@NonNull MemoryStore store) {
        this.store = store;
    }

    @Override
    public @NonNull List<ScoredMemory> search(
            @NonNull String query, @NonNull MemoryTier tier, int limit, float scoreFloor) {
        ToolCallContext ctx = CapabilityAccess.require(ToolCapability.MEMORY_READ, "recall_memory");
        UUID sessionId = ctx.sessionId();
        if (sessionId == null) {
            try {
                sessionId = UUID.fromString(ctx.agentId());
            } catch (IllegalArgumentException e) {
                return ToolErrors.failure(
                        ToolErrorCode.SESSION.NO_SESSION_CONTEXT,
                        "No session context: memories were not recalled.");
            }
        }
        return store.search(
                new MemoryQuery(
                        query,
                        List.of(tier),
                        tier == MemoryTier.SESSION ? sessionId : null,
                        null,
                        ctx.userId(),
                        limit,
                        scoreFloor));
    }
}
