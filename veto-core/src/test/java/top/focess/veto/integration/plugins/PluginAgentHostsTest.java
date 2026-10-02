package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.RequestHandle;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.agent.IsolatedAgent;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.builtin.web.ReaderConfig;
import top.focess.veto.builtin.web.WebReadSession;
import top.focess.veto.integration.plugins.storage.PluginStorageFactory;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.model.AgentEntity;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.plugin.runtime.PluginLifecycle;
import top.focess.veto.session.SessionHistoryLoader;
import top.focess.veto.vault.KeysteadVault;

class PluginAgentHostsTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void stoppingPluginCancelsItsActiveIsolatedChildBeforeDrainingParentCall(boolean cooperative)
            throws Exception {
        try (var fixture = new Fixture()) {
            var mapper = new ObjectMapper();
            ModelTierRegistry models = mock(ModelTierRegistry.class);
            when(models.resolve("owner", ModelTier.LOW))
                    .thenReturn(new ModelBinding(ProviderType.DEEPSEEK, "reader", "key", 0, 2048));
            when(fixture.storage.currentSession()).thenReturn(fixture.scope);
            VetoAgent parent = mock(VetoAgent.class);
            when(parent.id()).thenReturn(fixture.parent);
            when(parent.state()).thenReturn(AgentState.RUNNING);
            fixture.registry.register(UUID.fromString(fixture.session.getId()), parent);
            var entered = new CountDownLatch(1);
            var interrupted = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var disposed = new CountDownLatch(1);
            var requestHandle = new AtomicReference<AgentHost.Request>();
            try {
                var executions =
                        new IsolatedExecutions(
                                mapper,
                                (request, modelSessionId) -> {
                                    entered.countDown();
                                    while (true) {
                                        try {
                                            release.await();
                                            break;
                                        } catch (InterruptedException stopped) {
                                            interrupted.countDown();
                                            if (cooperative) {
                                                Thread.currentThread().interrupt();
                                                throw new CancellationException();
                                            }
                                        }
                                    }
                                    throw new CancellationException();
                                },
                                models,
                                new VetoCapabilityTranslator(),
                                fixture.registry,
                                new TurnLogService(null, mapper),
                                new IngressDefense(),
                                128,
                                600,
                                1048576,
                                65536);
                fixture.hosts.attachIsolated(executions);
                var base = ToolExecutionPermit.empty();
                var call = new ToolCall("reader_alias", Map.of(), "parent-call");
                var user = UUID.randomUUID();
                var permit =
                        new ToolExecutionPermit(
                                        call,
                                        ToolCapability.NETWORK_EGRESS,
                                        fixture.plugin.bindingId(),
                                        null,
                                        base.filesystemPaths(),
                                        base.workspaceRoots(),
                                        base.executionRoot(),
                                        base.deployerPolicy(),
                                        base.protectedPaths(),
                                        base.preparation())
                                .withCaller(
                                        fixture.parent,
                                        user,
                                        "owner",
                                        UUID.fromString(fixture.session.getId()));
                var context =
                        new ToolCallContext(
                                fixture.parent,
                                user,
                                "owner",
                                UUID.fromString(fixture.session.getId()),
                                ToolResultPresentationMode.BASIC,
                                permit);
                var parentResult =
                        CompletableFuture.runAsync(
                                () -> {
                                    ToolCallContextHolder.set(context);
                                    ReflectionTestUtils.invokeMethod(
                                            ToolCallContextHolder.class,
                                            "setCurrentCallId",
                                            call.callId());
                                    try {
                                        fixture.plugin.execute(
                                                () -> {
                                                    var spec = new ReaderConfig(Map.of()).spec();
                                                    try (var child =
                                                            fixture.host.isolate(
                                                                    spec,
                                                                    scope -> {
                                                                        var session =
                                                                                new WebReadSession(
                                                                                        scope,
                                                                                        mock());
                                                                        return new IsolatedAgent
                                                                                .Tools() {
                                                                            public @NonNull
                                                                                    List<
                                                                                            NativeTool<
                                                                                                    ?>>
                                                                                    tools() {
                                                                                return session
                                                                                        .tools();
                                                                            }

                                                                            public void close() {
                                                                                session.close();
                                                                                disposed
                                                                                        .countDown();
                                                                            }
                                                                        };
                                                                    })) {
                                                        var request = child.submit("Read document");
                                                        requestHandle.set(request);
                                                        assertFalse(
                                                                request.result().join().success());
                                                    }
                                                    return true;
                                                });
                                    } catch (Exception failure) {
                                        throw new CompletionException(failure);
                                    } finally {
                                        ToolCallContextHolder.clear();
                                    }
                                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var stopped = CompletableFuture.runAsync(fixture.plugin::close);
                assertTrue(interrupted.await(5, TimeUnit.SECONDS));
                if (cooperative) parentResult.get(5, TimeUnit.SECONDS);
                else
                    assertThrows(
                            ExecutionException.class, () -> parentResult.get(5, TimeUnit.SECONDS));
                stopped.get(5, TimeUnit.SECONDS);
                var handle = requestHandle.get();
                if (handle == null) throw new AssertionError();
                if (!cooperative) {
                    assertFalse(handle.settled().isDone());
                    assertEquals(1, disposed.getCount());
                    assertTrue(
                            fixture
                                    .registry
                                    .agents(UUID.fromString(fixture.session.getId()))
                                    .stream()
                                    .anyMatch(entry -> entry.ephemeral()));
                }
                release.countDown();
                assertTrue(disposed.await(5, TimeUnit.SECONDS));
                assertTrue(handle.settled().isDone());
                assertTrue(
                        fixture.registry.agents(UUID.fromString(fixture.session.getId())).stream()
                                .noneMatch(entry -> entry.ephemeral()));
            } finally {
                release.countDown();
            }
        }
    }

    static final class Fixture implements AutoCloseable {
        final @NonNull ExecutorService executor = Executors.newSingleThreadExecutor();
        final @NonNull PluginLifecycle plugin;
        final @NonNull SessionEntity session = new SessionEntity("owner", "test");
        final @NonNull String parent = UUID.randomUUID().toString();
        final @NonNull String childId = UUID.randomUUID().toString();
        final @NonNull PluginStorage storage = mock(PluginStorage.class);
        final @NonNull PluginStorageFactory scopes = mock(PluginStorageFactory.class);
        final @NonNull SessionRepository sessions = mock(SessionRepository.class);
        final @NonNull AgentInstanceRepository identities = mock(AgentInstanceRepository.class);
        final @NonNull SessionAgentRegistry registry = new SessionAgentRegistry();
        final @NonNull KeysteadVault vault = mock(KeysteadVault.class);
        final @NonNull AgentService service = mock(AgentService.class);
        final @NonNull SessionHistoryLoader history = mock(SessionHistoryLoader.class);
        final @NonNull VetoAgent agent = mock(VetoAgent.class);
        final @NonNull AgentEntity row;
        final PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> scope;
        final @NonNull AgentHost host;
        final @NonNull PluginAgentHosts hosts;
        final @NonNull AgentProfile profile =
                new AgentProfile("member", "work", "worker", Set.of(), null, null, Map.of());

        Fixture() throws Exception {
            VetoPlugin implementation = mock(VetoPlugin.class);
            var identity = new PluginIdentity("test.plugin", "1.0.0");
            when(implementation.identity()).thenReturn(identity);
            plugin = new PluginLifecycle(implementation, executor);
            plugin.construct(
                    new PluginContext(
                            identity,
                            () -> {},
                            () -> {
                                throw new IllegalStateException(
                                        "Plugin context is not bound to a lifecycle owner");
                            },
                            Map.of(),
                            Map.of()),
                    new JsonValue.ObjectValue(Map.of()));
            plugin.start();
            session.setPrimaryAgentId(parent);
            scope =
                    new PluginStorage.Grant<>(
                            "token", new Scope.SessionScope("owner", session.getId()));
            when(scopes.authorizeSession(storage, scope)).thenReturn("owner");
            when(vault.isUnlocked("owner")).thenReturn(true);
            when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
            var parentRow = AgentEntity.spawned(parent, session.getId(), "parent");
            when(identities.findById(parent)).thenReturn(Optional.of(parentRow));
            row = AgentEntity.spawned(childId, session.getId(), "child");
            row.claimPlugin("test.plugin", parent);
            when(identities.findById(childId)).thenReturn(Optional.of(row));
            when(agent.id()).thenReturn(childId);
            when(agent.state()).thenReturn(AgentState.IDLE);
            registry.register(UUID.fromString(session.getId()), agent);
            hosts =
                    new PluginAgentHosts(
                            PluginTestSupport.providerOf(service),
                            sessions,
                            identities,
                            registry,
                            scopes,
                            history,
                            vault);
            when(history.load(anyString(), anyString())).thenReturn(List.of());
            host = hosts.bind(plugin, storage);
        }

        AgentHost.@NonNull Child open() {
            return host.session(scope).open(childId, parent, profile);
        }

        public void close() {
            plugin.close();
            executor.close();
        }
    }

    @Test
    void rejectsRevokedScopeLockedOwnerAndAnotherPluginsIdentity() throws Exception {
        try (var fixture = new Fixture()) {
            when(fixture.vault.isUnlocked("owner")).thenReturn(false);
            assertThrows(SecurityException.class, fixture::open);
            when(fixture.vault.isUnlocked("owner")).thenReturn(true);
            var foreign = AgentEntity.spawned(fixture.childId, fixture.session.getId(), "foreign");
            foreign.claimPlugin("other.plugin", fixture.parent);
            when(fixture.identities.findById(fixture.childId)).thenReturn(Optional.of(foreign));
            assertThrows(SecurityException.class, fixture::open);
            var wrongSession =
                    AgentEntity.spawned(
                            fixture.childId, UUID.randomUUID().toString(), "foreign-session");
            wrongSession.claimPlugin("test.plugin", fixture.parent);
            when(fixture.identities.findById(fixture.childId))
                    .thenReturn(Optional.of(wrongSession));
            assertThrows(SecurityException.class, fixture::open);
            when(fixture.identities.findById(fixture.childId)).thenReturn(Optional.of(fixture.row));
            String otherParent = UUID.randomUUID().toString();
            var parentRow =
                    AgentEntity.spawned(otherParent, fixture.session.getId(), "other-parent");
            parentRow.claimPlugin("other.plugin", fixture.parent);
            when(fixture.identities.findById(otherParent)).thenReturn(Optional.of(parentRow));
            assertThrows(
                    SecurityException.class,
                    () ->
                            fixture.host
                                    .session(fixture.scope)
                                    .open(fixture.childId, otherParent, fixture.profile));
            when(fixture.scopes.authorizeSession(fixture.storage, fixture.scope))
                    .thenThrow(new SecurityException("revoked"));
            assertThrows(SecurityException.class, fixture::open);
            verifyNoInteractions(fixture.service);
        }
    }

    @Test
    void concurrentSameIdentityCreatesOneRowAndRuntime() throws Exception {
        try (var fixture = new Fixture();
                var callers = Executors.newFixedThreadPool(2)) {
            fixture.registry.stop(fixture.childId);
            var row = new AtomicReference<AgentEntity>();
            when(fixture.identities.findById(fixture.childId))
                    .thenAnswer(invocation -> Optional.ofNullable(row.get()));
            when(fixture.identities.saveAndFlush(any()))
                    .thenAnswer(
                            invocation -> {
                                var saved = invocation.<AgentEntity>getArgument(0);
                                if (saved == null)
                                    throw new AssertionError("Saved identity must exist");
                                row.set(saved);
                                return saved;
                            });
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var attempts = new AtomicInteger();
            when(fixture.service.openPluginAgent(
                            any(), anyString(), anyString(), anyString(), any(), any()))
                    .thenAnswer(
                            invocation -> {
                                attempts.incrementAndGet();
                                entered.countDown();
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                fixture.registry.register(
                                        UUID.fromString(fixture.session.getId()), fixture.agent);
                                return fixture.agent;
                            });
            var first = callers.submit(fixture::open);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var started = new CountDownLatch(1);
                var second =
                        callers.submit(
                                () -> {
                                    started.countDown();
                                    return fixture.open();
                                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                assertEquals(fixture.childId, first.get(5, TimeUnit.SECONDS).id());
                assertEquals(fixture.childId, second.get(5, TimeUnit.SECONDS).id());
                assertEquals(1, attempts.get());
                verify(fixture.identities).saveAndFlush(any());
                assertEquals(
                        1,
                        fixture.registry.agents(UUID.fromString(fixture.session.getId())).size());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void differentIdentityStripesCanCreateWhileAnotherCreationIsBlocked() throws Exception {
        try (var fixture = new Fixture();
                var callers = Executors.newFixedThreadPool(2)) {
            fixture.registry.stop(fixture.childId);
            UUID firstId = UUID.fromString(fixture.childId);
            // Flipping one low bit selects another stripe without relying on random hash
            // collisions.
            String otherId =
                    new UUID(
                                    firstId.getMostSignificantBits(),
                                    firstId.getLeastSignificantBits() ^ 1)
                            .toString();
            when(fixture.identities.findById(fixture.childId)).thenReturn(Optional.empty());
            when(fixture.identities.findById(otherId)).thenReturn(Optional.empty());
            var other = mock(VetoAgent.class);
            when(other.id()).thenReturn(otherId);
            when(other.state()).thenReturn(AgentState.IDLE);
            var entered = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            when(fixture.service.openPluginAgent(
                            any(), anyString(), anyString(), anyString(), any(), any()))
                    .thenAnswer(
                            invocation -> {
                                String id = invocation.getArgument(1);
                                if (id == null)
                                    throw new AssertionError("Child identity must exist");
                                entered.countDown();
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                var created = id.equals(otherId) ? other : fixture.agent;
                                fixture.registry.register(
                                        UUID.fromString(fixture.session.getId()), created);
                                return created;
                            });
            var first = callers.submit(fixture::open);
            var second =
                    callers.submit(
                            () ->
                                    fixture.host
                                            .session(fixture.scope)
                                            .open(otherId, fixture.parent, fixture.profile));
            try {
                assertTrue(
                        entered.await(5, TimeUnit.SECONDS),
                        "Independent child creation must overlap");
                release.countDown();
                assertEquals(fixture.childId, first.get(5, TimeUnit.SECONDS).id());
                assertEquals(otherId, second.get(5, TimeUnit.SECONDS).id());
                assertEquals(
                        2,
                        fixture.registry.agents(UUID.fromString(fixture.session.getId())).size());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void conflictingPluginCannotClaimIdentityDuringItsCreation() throws Exception {
        try (var fixture = new Fixture();
                var callers = Executors.newFixedThreadPool(2)) {
            fixture.registry.stop(fixture.childId);
            var row = new AtomicReference<AgentEntity>();
            when(fixture.identities.findById(fixture.childId))
                    .thenAnswer(invocation -> Optional.ofNullable(row.get()));
            when(fixture.identities.saveAndFlush(any()))
                    .thenAnswer(
                            invocation -> {
                                var saved = invocation.<AgentEntity>getArgument(0);
                                if (saved == null)
                                    throw new AssertionError("Saved identity must exist");
                                row.set(saved);
                                return saved;
                            });
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            when(fixture.service.openPluginAgent(
                            any(), anyString(), anyString(), anyString(), any(), any()))
                    .thenAnswer(
                            invocation -> {
                                entered.countDown();
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                fixture.registry.register(
                                        UUID.fromString(fixture.session.getId()), fixture.agent);
                                return fixture.agent;
                            });
            var foreign = mock(PluginLifecycle.class);
            when(foreign.identity()).thenReturn(new PluginIdentity("foreign.plugin", "1.0.0"));
            var host = fixture.hosts.bind(foreign, fixture.storage);
            var first = callers.submit(fixture::open);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var started = new CountDownLatch(1);
                var conflict =
                        callers.submit(
                                () -> {
                                    started.countDown();
                                    return host.session(fixture.scope)
                                            .open(fixture.childId, fixture.parent, fixture.profile);
                                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(
                        TimeoutException.class, () -> conflict.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                assertEquals(fixture.childId, first.get(5, TimeUnit.SECONDS).id());
                var rejected =
                        assertThrows(
                                ExecutionException.class, () -> conflict.get(5, TimeUnit.SECONDS));
                assertInstanceOf(SecurityException.class, rejected.getCause());
                verify(fixture.service)
                        .openPluginAgent(
                                any(), anyString(), anyString(), anyString(), any(), any());
                verify(foreign, never()).ownResource(any(), any());
            } finally {
                release.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedOwnershipReleasesCreatedRuntimeWithoutStoppingReplacement(boolean replaced)
            throws Exception {
        try (var fixture = new Fixture()) {
            fixture.registry.stop(fixture.childId);
            clearInvocations(fixture.agent);
            var replacement = mock(VetoAgent.class);
            when(replacement.id()).thenReturn(fixture.childId);
            when(replacement.state()).thenReturn(AgentState.IDLE);
            when(fixture.service.openPluginAgent(
                            any(), anyString(), anyString(), anyString(), any(), any()))
                    .thenAnswer(
                            invocation -> {
                                fixture.registry.register(
                                        UUID.fromString(fixture.session.getId()),
                                        replaced ? replacement : fixture.agent);
                                fixture.plugin.close();
                                return fixture.agent;
                            });
            assertThrows(IllegalStateException.class, fixture::open);
            verify(fixture.agent).terminate();
            verify(replacement, never()).terminate();
            var live = fixture.registry.agents(UUID.fromString(fixture.session.getId()));
            if (replaced) assertSame(replacement, live.getFirst().agent());
            else assertTrue(live.isEmpty());
        }
    }

    @Test
    void repeatedOpenHasOneCleanupAndStaleHandleCannotStopReplacement() throws Exception {
        try (var fixture = new Fixture()) {
            var first = fixture.open();
            fixture.open();
            fixture.open();
            fixture.registry.stop(fixture.childId);
            clearInvocations(fixture.agent);
            VetoAgent replacement = mock(VetoAgent.class);
            when(replacement.id()).thenReturn(fixture.childId);
            when(replacement.state()).thenReturn(AgentState.IDLE);
            fixture.registry.register(UUID.fromString(fixture.session.getId()), replacement);
            first.close();
            verify(replacement, never()).terminate();
            assertSame(
                    replacement,
                    fixture.registry
                            .agents(UUID.fromString(fixture.session.getId()))
                            .getFirst()
                            .agent());
            fixture.plugin.close();
            verify(fixture.agent).terminate();
        }
        try (var fixture = new Fixture()) {
            fixture.open();
            fixture.open();
            fixture.open();
            fixture.plugin.close();
            verify(fixture.agent).terminate();
        }
    }

    @Test
    void pluginCannotCompleteHostFuturesAndCancellationDoesNotFabricateSettlement()
            throws Exception {
        try (var fixture = new Fixture()) {
            RequestHandle handle = mock(RequestHandle.class);
            CompletableFuture<AgentResult> result = new CompletableFuture<>();
            CompletableFuture<Boolean> settled = new CompletableFuture<>();
            when(handle.requestId()).thenReturn("request");
            when(handle.result()).thenReturn(result);
            when(handle.settled()).thenReturn(settled);
            when(fixture.agent.submitRequest("work")).thenReturn(handle);
            var child = fixture.open();
            var request = child.submit("work");
            request.result().complete(AgentResult.success("forged", Map.of()));
            request.settled().complete(true);
            assertFalse(result.isDone());
            assertFalse(settled.isDone());
            assertFalse(request.cancel(Duration.ZERO));
            verify(fixture.agent).cancelTask(result, Duration.ZERO);
            child.close();
            assertFalse(request.settled().isDone());
            assertFalse(child.awaitTermination(Duration.ZERO));
            result.complete(AgentResult.failure("cancelled", Map.of()));
            assertFalse(request.settled().isDone());
            settled.complete(true);
            assertTrue(request.settled().join());
            assertEquals("cancelled", request.result().join().message());
        }
    }
}
