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
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
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
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
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
import top.focess.veto.plugin.runtime.ManagedPlugin;
import top.focess.veto.session.SessionHistoryLoader;
import top.focess.veto.vault.KeysteadVault;

@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
class PluginAgentHostsTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void stoppingPluginCancelsItsActiveIsolatedChildBeforeDrainingParentCall(boolean cooperative)
            throws Exception {
        try (var fixture = new Fixture()) {
            var mapper = new ObjectMapper();
            @NonNull ModelTierRegistry models = mock();
            when(models.resolve("owner", ModelTier.LOW))
                    .thenReturn(new ModelBinding(ProviderType.DEEPSEEK, "reader", "key", 0, 2048));
            when(fixture.storage.currentSession()).thenReturn(fixture.scope);
            @NonNull VetoAgent parent = mock();
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
                                request -> {
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
                                new DefaultCapabilityTranslator(mapper),
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
                                                                            public List<
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
                            ToolDocs.nonNullClass(ExecutionException.class),
                            () -> parentResult.get(5, TimeUnit.SECONDS));
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
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final ManagedPlugin plugin;
        final SessionEntity session = new SessionEntity("owner", "test");
        final String parent = UUID.randomUUID().toString();
        final String childId = UUID.randomUUID().toString();
        final PluginStorage storage = mock();
        final PluginStorageFactory scopes = mock();
        final SessionRepository sessions = mock();
        final AgentInstanceRepository identities = mock();
        final SessionAgentRegistry registry = new SessionAgentRegistry();
        final KeysteadVault vault = mock();
        final AgentService service = mock();
        final VetoAgent agent = mock();
        final AgentEntity row;
        final PluginStorage.SessionScope scope;
        final AgentHost host;
        final PluginAgentHosts hosts;
        final AgentProfile profile =
                new AgentProfile("member", "work", "worker", Set.of(), null, null, Map.of());

        Fixture() throws Exception {
            @NonNull VetoPlugin implementation = mock();
            var identity = new PluginIdentity("test.plugin", "1.0.0");
            when(implementation.identity()).thenReturn(identity);
            when(implementation.initialize(any(), any()))
                    .thenReturn(new PluginContributions(List.of()));
            plugin = new ManagedPlugin(implementation, executor);
            plugin.initialize(new PluginContext(identity), new JsonValue.ObjectValue(Map.of()));
            plugin.start();
            session.setPrimaryAgentId(parent);
            scope = new PluginStorage.SessionScope("token", "owner", session.getId());
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
                            mock(ToolDocs.nonNullClass(SessionHistoryLoader.class)),
                            vault);
            host = hosts.bind(plugin, storage);
        }

        AgentHost.Child open() {
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
    void repeatedOpenHasOneCleanupAndStaleHandleCannotStopReplacement() throws Exception {
        try (var fixture = new Fixture()) {
            var first = fixture.open();
            fixture.open();
            fixture.open();
            fixture.registry.stop(fixture.childId);
            clearInvocations(fixture.agent);
            @NonNull VetoAgent replacement = mock();
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
            @NonNull RequestHandle handle = mock();
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
