package top.focess.veto.builtin.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.builtin.memory.embedder.HashEmbedder;

class MemoryRuntimeTest {
    private final @NonNull PluginHost host = mock(PluginHost.class);
    private final @NonNull PluginStorage storage = mock(PluginStorage.class);
    private final @NonNull UUID session = UUID.randomUUID();

    private @NonNull MemoryRuntime runtime(
            @NonNull String owner,
            @NonNull String profile,
            @Nullable MemoryBackendFactory backend) {
        return runtime(owner, UUID.randomUUID(), profile, backend);
    }

    private @NonNull MemoryRuntime runtime(
            @NonNull String owner,
            @NonNull UUID userIdentity,
            @NonNull String profile,
            @Nullable MemoryBackendFactory backend) {
        when(host.invocation(anyString()))
                .thenAnswer(
                        call ->
                                new PluginHost.Invocation(
                                        owner, session.toString(), "agent", "request", "call"));
        when(storage.currentSession())
                .thenReturn(
                        new PluginStorage.SessionScope(
                                "token", userIdentity.toString(), session.toString()));
        when(storage.currentUser())
                .thenReturn(new PluginStorage.UserScope("token", userIdentity.toString()));
        var services = new java.util.HashMap<Class<?>, Object>();
        services.put(PluginHost.class, host);
        services.put(PluginStorage.class, storage);
        if (backend != null)
            services.put(ToolDocs.nonNullClass(MemoryBackendFactory.class), backend);
        return new MemoryRuntime(
                new PluginContext(new PluginIdentity("top.focess.builtin", "1.0.0"), services),
                new JsonValue.ObjectValue(
                        Map.of("memory-store", new JsonValue.StringValue(profile))));
    }

    @Test
    void volatileProfilesWriteRecallForgetWithoutHostMemoryCapability() {
        for (String profile : List.of("memory", "vector")) {
            UUID identity = UUID.randomUUID();
            var runtime = runtime("alice", identity, profile, null);
            var write = new MemoryTools.WriteMemory(runtime.writer("write_memory"));
            String result =
                    write.execute(
                            new MemoryTools.WriteMemory.Args(
                                    MemoryTools.WriteMemory.Mode.WRITE,
                                    "important decision",
                                    null,
                                    null));
            assertTrue(result.startsWith("memory written: "));
            var matches =
                    runtime.reader().search("important decision", MemoryTier.CROSS_SESSION, 5, .5f);
            assertEquals(1, matches.size());
            assertEquals(identity, matches.getFirst().memory().userId());
            assertTrue(runtime.writer("forget_memory").forget(matches.getFirst().memory().id()));
            assertTrue(
                    runtime.reader()
                            .search("important decision", MemoryTier.CROSS_SESSION, 5, .5f)
                            .isEmpty());
        }
    }

    @Test
    void sameNameAccountCannotReadMemoryFromAnotherStorageIdentity() {
        var store = new InMemoryMemoryStore(new HashEmbedder());
        UUID oldIdentity = UUID.randomUUID();
        UUID newIdentity = UUID.randomUUID();
        var text = "legacy decision";
        var current =
                new Memory(
                        MemoryId.random(),
                        oldIdentity,
                        session,
                        MemoryTier.SESSION,
                        null,
                        text,
                        new HashEmbedder().embed(text),
                        Memory.SourceRef.insightOrigin("legacy"),
                        Instant.now());
        store.add(current);
        var runtime = runtime("alice", newIdentity, "jpa", (profile, embedder) -> store);
        assertTrue(runtime.reader().search(text, MemoryTier.SESSION, 5, .5f).isEmpty());
        assertNull(runtime.writer("write_memory").promote(current.id()));
        assertFalse(runtime.writer("forget_memory").forget(current.id()));
    }

    @Test
    void ownerDeletionBlocksWritesAndRollbackRestoresAccess() {
        UUID identity = UUID.randomUUID();
        var runtime = runtime("alice", identity, "memory", null);
        var writer = runtime.writer("write_memory");
        writer.add("retained on rollback", null);

        var completion = runtime.prepareOwnerDeletion("alice", identity.toString());
        assertThrows(SecurityException.class, () -> writer.add("blocked", null));
        completion.complete(false);

        assertEquals(
                1, runtime.reader().search("retained", MemoryTier.CROSS_SESSION, 5, .1f).size());
    }

    @Test
    void committedOwnerDeletionClearsVolatileMemoryAndKeepsScopeBlocked() {
        UUID identity = UUID.randomUUID();
        var runtime = runtime("alice", identity, "vector", null);
        runtime.writer("write_memory").add("erase me", null);

        runtime.prepareOwnerDeletion("alice", identity.toString()).complete(true);

        assertThrows(
                SecurityException.class,
                () -> runtime.reader().search("erase", MemoryTier.CROSS_SESSION, 5, .1f));
    }

    @Test
    void durableCleanupFailureReleasesDeletionMarker() {
        UUID identity = UUID.randomUUID();
        var factory = mock(ToolDocs.nonNullClass(MemoryBackendFactory.class));
        doThrow(new IllegalStateException("cleanup failed")).when(factory).deleteOwner(identity);
        var runtime = runtime("alice", identity, "jpa", factory);

        assertThrows(
                IllegalStateException.class,
                () -> runtime.prepareOwnerDeletion("alice", identity.toString()));

        when(factory.open(eq("jpa"), any()))
                .thenReturn(new InMemoryMemoryStore(new HashEmbedder()));
        assertDoesNotThrow(() -> runtime.writer("write_memory").add("still writable", null));
    }

    @Test
    void durableProfilesAreLazyAndPreserveTheSelectedAdapter() {
        for (String profile : List.of("jpa", "pgvector")) {
            var factory = mock(ToolDocs.nonNullClass(MemoryBackendFactory.class));
            var store = mock(ToolDocs.nonNullClass(MemoryStore.class));
            when(factory.open(eq(profile), any())).thenReturn(store);
            when(store.search(any())).thenReturn(List.of());
            var runtime = runtime("alice", profile, factory);
            verifyNoInteractions(factory);
            runtime.reader().search("query", MemoryTier.SESSION, 5, .5f);
            runtime.reader().search("query", MemoryTier.CROSS_SESSION, 5, .5f);
            verify(factory, times(1)).open(eq(profile), any());
        }
    }

    @Test
    void absentInvocationCannotReachBackendAndWrongOperationCannotMutate() {
        var factory = mock(ToolDocs.nonNullClass(MemoryBackendFactory.class));
        var runtime = runtime("alice", "jpa", factory);
        when(host.invocation(anyString())).thenThrow(new SecurityException("no invocation"));
        assertThrows(
                SecurityException.class,
                () -> runtime.reader().search("query", MemoryTier.SESSION, 5, .5f));
        verifyNoInteractions(factory);
        doReturn(new PluginHost.Invocation("alice", session.toString(), "agent", "request", "call"))
                .when(host)
                .invocation(anyString());
        assertThrows(
                SecurityException.class,
                () -> runtime.writer("forget_memory").add("invalid", null));
        verifyNoInteractions(factory);
    }
}
