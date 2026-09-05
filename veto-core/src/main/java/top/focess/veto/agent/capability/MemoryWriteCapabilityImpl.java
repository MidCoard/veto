package top.focess.veto.agent.capability;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.memory.Memory;
import top.focess.veto.memory.MemoryId;
import top.focess.veto.memory.MemoryStore;
import top.focess.veto.memory.MemoryTier;
import top.focess.veto.memory.MemoryTools.ForgetMemory;
import top.focess.veto.memory.MemoryTools.WriteMemory;
import top.focess.veto.memory.embedder.Embedder;
import top.focess.veto.util.Nullness;

@Component
public final class MemoryWriteCapabilityImpl implements MemoryWriteCapability {
    private static final int MAX_MEMORY_CHARS = 64_000;
    private final @NonNull MemoryStore store;
    private final @NonNull Embedder embedder;

    public MemoryWriteCapabilityImpl(@NonNull MemoryStore store, @NonNull Embedder embedder) {
        this.store = store;
        this.embedder = embedder;
    }

    @Override
    public @NonNull String write(WriteMemory.@NonNull Args args) {
        ToolCallContext ctx =
                CapabilityAccess.require(ToolCapability.MEMORY_WRITE, "write_memory", args);
        String requestedContent = args.content();
        String requestedProjectId = args.projectId();
        String requestedPromoteId = args.promoteMemoryId();
        if (args.mode() == WriteMemory.Mode.PROMOTE) {
            if ((requestedContent != null && !requestedContent.isBlank())
                    || (requestedProjectId != null && !requestedProjectId.isBlank())) {
                return ToolErrors.failure(
                        "PROMOTE accepts only promoteMemoryId; memory not promoted");
            }
            String promoteId =
                    Nullness.requireNonNull(
                            requestedPromoteId,
                            "RequiredWhen validation must supply promoteMemoryId");
            try {
                MemoryId promoted =
                        store.promote(
                                new MemoryId(UUID.fromString(promoteId.strip())), ctx.userId());
                return promoted != null
                        ? "promoted: " + promoted.value()
                        : ToolErrors.failure("memory not found or not owned; not promoted");
            } catch (IllegalArgumentException e) {
                return ToolErrors.failure("memory not found or not owned; not promoted");
            }
        }
        if (requestedPromoteId != null && !requestedPromoteId.isBlank()) {
            return ToolErrors.failure("WRITE does not accept promoteMemoryId; memory not written");
        }
        String content =
                Nullness.requireNonNull(
                        requestedContent, "RequiredWhen validation must supply content");
        if (content.length() > MAX_MEMORY_CHARS) {
            return ToolErrors.failure("memory exceeds 64000 characters; not written");
        }
        UUID projectId = parseUuidOrNull(requestedProjectId);
        if (requestedProjectId != null && !requestedProjectId.isBlank() && projectId == null) {
            return ToolErrors.failure("invalid projectId; memory not written");
        }
        Memory m =
                new Memory(
                        MemoryId.random(),
                        ctx.userId(),
                        null, // CROSS_SESSION strips the sessionId (the curating boundary)
                        MemoryTier.CROSS_SESSION,
                        projectId,
                        content,
                        embedder.embed(content),
                        Memory.SourceRef.insightOrigin("write_memory"),
                        Instant.now());
        MemoryId id = store.add(m);
        return "memory written: " + id.value();
    }

    @Override
    public @NonNull String forget(ForgetMemory.@NonNull Args args) {
        ToolCallContext ctx =
                CapabilityAccess.require(ToolCapability.MEMORY_WRITE, "forget_memory", args);
        String id = args.memoryId();
        if (id.isBlank()) {
            return ToolErrors.failure("memory not found or not owned; nothing forgotten");
        }
        try {
            MemoryId memoryId = new MemoryId(UUID.fromString(id.strip()));
            boolean forgotten = store.forget(memoryId, ctx.userId());
            return forgotten
                    ? "forgotten: " + memoryId.value()
                    : ToolErrors.failure("memory not found or not owned; nothing forgotten");
        } catch (IllegalArgumentException e) {
            return ToolErrors.failure("memory not found or not owned; nothing forgotten");
        }
    }

    static UUID parseUuidOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
