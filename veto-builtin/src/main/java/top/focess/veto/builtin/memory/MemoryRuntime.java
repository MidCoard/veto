package top.focess.veto.builtin.memory;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.llm.TextEmbedding;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.DataLifecycle;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.builtin.memory.embedder.Embedder;
import top.focess.veto.builtin.memory.embedder.HashEmbedder;

/** Owns memory backend selection and caller-bound feature policy for one builtin activation. */
public final class MemoryRuntime implements DataLifecycle {
    private final @NonNull Set<String> deletingOwners = new HashSet<>();
    private final @NonNull Set<String> deletingSessions = new HashSet<>();
    private final @NonNull PluginContext context;
    private final @NonNull String profile;
    private final @NonNull Embedder embedder;
    private MemoryStore store;

    public MemoryRuntime(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        this.context = context;
        var selected = configuration.values().get("memory-store");
        profile = selected instanceof JsonValue.StringValue value ? value.value() : "memory";
        if (!List.of("memory", "vector", "jpa", "pgvector").contains(profile))
            throw new IllegalArgumentException("Unknown memory backend");
        var remote = context.service(ToolDocs.nonNullClass(TextEmbedding.class)).orElse(null);
        embedder =
                remote == null
                        ? new HashEmbedder()
                        : new Embedder() {
                            public float @NonNull [] embed(@NonNull String text) {
                                try {
                                    return remote.embed(text);
                                } catch (IllegalStateException failure) {
                                    return ToolErrors.failure(
                                            () -> "MEMORY_EMBEDDING_FAILED",
                                            "Memory embedding failed; memory operation did not complete.");
                                }
                            }

                            public int dimension() {
                                return remote.dimension();
                            }
                        };
    }

    private synchronized @NonNull MemoryStore store() {
        if (store == null)
            store =
                    switch (profile) {
                        case "memory" -> new InMemoryMemoryStore(embedder);
                        case "vector" -> new VectorIndexMemoryStore(new VectorIndex(), embedder);
                        default ->
                                context.service(ToolDocs.nonNullClass(MemoryBackendFactory.class))
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "Durable memory backend unavailable"))
                                        .open(profile, embedder);
                    };
        return store;
    }

    private PluginHost.@NonNull Invocation authorize(@NonNull String tool) {
        return context.service(ToolDocs.nonNullClass(PluginHost.class))
                .orElseThrow(() -> new SecurityException("Plugin invocation unavailable"))
                .invocation(tool);
    }

    private PluginStorage.@NonNull SessionScope scope(@NonNull String tool) {
        var invocation = authorize(tool);
        var scope = context.storage().currentSession();
        if (!scope.sessionId().equals(invocation.sessionId())
                || deletingOwners.contains(scope.userId())
                || deletingSessions.contains(scope.userId() + ":" + scope.sessionId()))
            throw new SecurityException("Memory scope is being deleted");
        return scope;
    }

    @Override
    public synchronized @NonNull Completion prepareOwnerDeletion(
            @NonNull String owner, @NonNull String userId) {
        if (!deletingOwners.add(userId))
            throw new IllegalStateException("Account cleanup already pending");
        try {
            var backend =
                    context.service(ToolDocs.nonNullClass(MemoryBackendFactory.class)).orElse(null);
            if (backend != null) backend.deleteOwner(UUID.fromString(userId));
            else if (profile.equals("jpa") || profile.equals("pgvector"))
                throw new IllegalStateException("Durable cleanup unavailable");
        } catch (RuntimeException failure) {
            deletingOwners.remove(userId);
            throw failure;
        }
        return committed -> {
            synchronized (MemoryRuntime.this) {
                if (committed
                        && store != null
                        && (profile.equals("memory") || profile.equals("vector")))
                    store.deleteOwner(UUID.fromString(userId));
                if (!committed) deletingOwners.remove(userId);
            }
        };
    }

    @Override
    public synchronized @NonNull Completion prepareSessionDeletion(
            @NonNull String owner, @NonNull String userId, @NonNull String sessionId) {
        String key = userId + ":" + sessionId;
        if (!deletingSessions.add(key))
            throw new IllegalStateException("Session cleanup already pending");
        try {
            var backend =
                    context.service(ToolDocs.nonNullClass(MemoryBackendFactory.class)).orElse(null);
            if (backend != null)
                backend.deleteSession(UUID.fromString(userId), UUID.fromString(sessionId));
            else if (profile.equals("jpa") || profile.equals("pgvector"))
                throw new IllegalStateException("Durable cleanup unavailable");
        } catch (RuntimeException failure) {
            deletingSessions.remove(key);
            throw failure;
        }
        return committed -> {
            synchronized (MemoryRuntime.this) {
                if (committed
                        && store != null
                        && (profile.equals("memory") || profile.equals("vector")))
                    store.deleteSession(UUID.fromString(userId), UUID.fromString(sessionId));
                if (!committed) deletingSessions.remove(key);
            }
        };
    }

    public @NonNull MemoryReadCapability reader() {
        return (query, tier, limit, floor) -> {
            synchronized (MemoryRuntime.this) {
                var scope = scope("recall_memory");
                return store().search(
                                new MemoryQuery(
                                        query,
                                        List.of(tier),
                                        tier == MemoryTier.SESSION
                                                ? UUID.fromString(scope.sessionId())
                                                : null,
                                        null,
                                        UUID.fromString(scope.userId()),
                                        limit,
                                        floor));
            }
        };
    }

    public @NonNull MemoryWriteCapability writer(@NonNull String tool) {
        return new MemoryWriteCapability() {
            public @NonNull MemoryId add(@NonNull String content, UUID projectId) {
                synchronized (MemoryRuntime.this) {
                    UUID user = UUID.fromString(scope(tool).userId());
                    if (!tool.equals("write_memory"))
                        throw new SecurityException("Memory operation mismatch");
                    return store().add(
                                    new Memory(
                                            MemoryId.random(),
                                            user,
                                            null,
                                            MemoryTier.CROSS_SESSION,
                                            projectId,
                                            content,
                                            embedder.embed(content),
                                            Memory.SourceRef.insightOrigin("write_memory"),
                                            Instant.now()));
                }
            }

            public MemoryId promote(@NonNull MemoryId id) {
                synchronized (MemoryRuntime.this) {
                    UUID user = UUID.fromString(scope(tool).userId());
                    if (!tool.equals("write_memory"))
                        throw new SecurityException("Memory operation mismatch");
                    return store().promote(id, user);
                }
            }

            public boolean forget(@NonNull MemoryId id) {
                synchronized (MemoryRuntime.this) {
                    UUID user = UUID.fromString(scope(tool).userId());
                    if (!tool.equals("forget_memory"))
                        throw new SecurityException("Memory operation mismatch");
                    return store().forget(id, user);
                }
            }
        };
    }
}
