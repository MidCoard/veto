package top.focess.veto.agent.capability;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.memory.Memory;
import top.focess.veto.memory.MemoryId;
import top.focess.veto.memory.MemoryStore;
import top.focess.veto.memory.MemoryTier;
import top.focess.veto.memory.embedder.Embedder;

@Component
public final class MemoryWriteCapabilityImpl implements MemoryWriteCapability {
    private final @NonNull MemoryStore store;
    private final @NonNull Embedder embedder;

    public MemoryWriteCapabilityImpl(@NonNull MemoryStore store, @NonNull Embedder embedder) {
        this.store = store;
        this.embedder = embedder;
    }

    @Override
    public @NonNull MemoryId add(@NonNull String content, UUID projectId) {
        ToolCallContext ctx = CapabilityAccess.require(ToolCapability.MEMORY_WRITE, "write_memory");
        return store.add(
                new Memory(
                        MemoryId.random(),
                        ctx.userId(),
                        null,
                        MemoryTier.CROSS_SESSION,
                        projectId,
                        content,
                        embedder.embed(content),
                        Memory.SourceRef.insightOrigin("write_memory"),
                        Instant.now()));
    }

    @Override
    public MemoryId promote(@NonNull MemoryId id) {
        var ctx = CapabilityAccess.require(ToolCapability.MEMORY_WRITE, "write_memory");
        return store.promote(id, ctx.userId());
    }

    @Override
    public boolean forget(@NonNull MemoryId id) {
        var ctx = CapabilityAccess.require(ToolCapability.MEMORY_WRITE, "forget_memory");
        return store.forget(id, ctx.userId());
    }
}
