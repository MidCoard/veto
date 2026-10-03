package top.focess.veto.builtin.memory;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.llm.TextEmbedding;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.contract.DataLifecycle;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.builtin.memory.embedder.Embedder;
import top.focess.veto.builtin.memory.embedder.HashEmbedder;

/**
 * Owns memory backend selection and caller-bound feature policy for one builtin activation.
 *
 * <p>Tool operations and account/session deletion callbacks share the runtime monitor. It protects
 * lazy backend initialization and deletion markers, and keeps authorization, embedding and backend
 * access inside the same exclusion boundary so writes cannot pass a prepared deletion. Transaction
 * completion callbacks reacquire this monitor. Backend and embedding implementations must not wait
 * for another operation on this runtime; slow I/O serializes callers by design.
 */
public final class MemoryRuntime implements DataLifecycle {
    private final @NonNull Set<String> deletingOwners = new HashSet<>();
    private final @NonNull Set<String> deletingSessions = new HashSet<>();
    private final @NonNull PluginContext context;
    private final @NonNull String profile;
    private final @NonNull Embedder embedder;
    private MemoryStore store;

    /** Selects the memory backend named in the configuration and wires the embedder. */
    public MemoryRuntime(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        this.context = context;
        var selected = configuration.values().get("memory-store");
        profile = selected instanceof JsonValue.StringValue(String value) ? value : "memory";
        if (!List.of("memory", "vector", "jpa", "pgvector").contains(profile))
            throw new IllegalArgumentException("Unknown memory backend");
        var remote = context.service(TextEmbedding.class).orElse(null);
        embedder =
                remote == null
                        ? new HashEmbedder()
                        : new Embedder() {
                            public float @NonNull [] embed(@NonNull String text) {
                                try {
                                    return remote.embed(text);
                                } catch (IllegalStateException failure) {
                                    return ToolErrors.failure(
                                            ToolErrorCode.MEMORY.MEMORY_EMBEDDING_FAILED,
                                            "Memory embedding failed; memory operation did not"
                                                    + " complete.");
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
                        case "vector" -> new InMemoryMemoryStore(embedder, true);
                        default ->
                                context.service(MemoryBackendFactory.class)
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "Durable memory backend"
                                                                        + " unavailable"))
                                        .open(profile, embedder);
                    };
        return store;
    }

    private PluginHost.@NonNull Invocation authorize(@NonNull String tool) {
        return context.service(PluginHost.class)
                .orElseThrow(() -> new SecurityException("Plugin invocation unavailable"))
                .invocation(tool);
    }

    private PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> grant(@NonNull String tool) {
        var invocation = authorize(tool);
        var grant = context.storage().currentSession();
        if (!grant.scope().session().equals(invocation.sessionId())
                || deletingOwners.contains(grant.scope().owner())
                || deletingSessions.contains(grant.scope().owner() + ":" + grant.scope().session()))
            throw new SecurityException("Memory scope is being deleted");
        return grant;
    }

    @Override
    public synchronized @NonNull Completion prepareOwnerDeletion(
            @NonNull String owner, @NonNull String userId) {
        if (!deletingOwners.add(userId))
            throw new IllegalStateException("Account cleanup already pending");
        try {
            var backend = context.service(MemoryBackendFactory.class).orElse(null);
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
            var backend = context.service(MemoryBackendFactory.class).orElse(null);
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

    /** Returns a scope-checked read capability for {@code recall_memory}. */
    public @NonNull MemoryReadCapability reader() {
        return (query, tier, limit, floor) -> {
            synchronized (MemoryRuntime.this) {
                var grant = grant("recall_memory");
                return store().search(
                                new MemoryQuery(
                                        query,
                                        List.of(tier),
                                        tier == MemoryTier.SESSION
                                                ? UUID.fromString(grant.scope().session())
                                                : null,
                                        null,
                                        UUID.fromString(grant.scope().owner()),
                                        limit,
                                        floor));
            }
        };
    }

    /** Returns a scope-checked write capability bound to the calling tool name. */
    public @NonNull MemoryWriteCapability writer(@NonNull String tool) {
        return new MemoryWriteCapability() {
            public @NonNull MemoryId add(@NonNull String content, UUID projectId) {
                synchronized (MemoryRuntime.this) {
                    UUID user = UUID.fromString(grant(tool).scope().owner());
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
                    UUID user = UUID.fromString(grant(tool).scope().owner());
                    if (!tool.equals("write_memory"))
                        throw new SecurityException("Memory operation mismatch");
                    return store().promote(id, user);
                }
            }

            public boolean forget(@NonNull MemoryId id) {
                synchronized (MemoryRuntime.this) {
                    UUID user = UUID.fromString(grant(tool).scope().owner());
                    if (!tool.equals("forget_memory"))
                        throw new SecurityException("Memory operation mismatch");
                    return store().forget(id, user);
                }
            }
        };
    }
}
