package top.focess.veto.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.continuation.RequestContinuationStore;
import top.focess.veto.agent.intercept.HitlRecordRepository;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.DeployerPolicyConfiguration;
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.screening.ProtectedSetResolver;
import top.focess.veto.agent.workspace.WorkspaceAdmissionPolicy;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.event.EventManager;
import top.focess.veto.integration.plugins.PluginDataCleanup;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.storage.ScopedPluginStorage;
import top.focess.veto.model.AgentEntity;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTierRegistry;

class SessionCreationTest {
    private final @NonNull SessionRepository sessions = mock();
    private final @NonNull AgentInstanceRepository agents = mock();
    private final @NonNull AgentPatternRepository patterns = mock();
    private final @NonNull PluginManager plugins = mock();
    private final @NonNull ModelTierRegistry tiers = mock();
    private final @NonNull DeployerPolicyConfiguration configuration =
            new DeployerPolicyConfiguration();
    private final @NonNull ProtectedSetResolver protectedSets = mock();
    private final @NonNull SessionService service =
            new SessionService(
                    sessions,
                    agents,
                    patterns,
                    mock(AgentService.class),
                    mock(SessionAgentRegistry.class),
                    mock(SessionHistoryLoader.class),
                    tiers,
                    new WorkspaceAdmissionPolicy(configuration, protectedSets),
                    mock(ScopedPluginStorage.class),
                    mock(HitlRecordRepository.class),
                    plugins,
                    mock(EventManager.class),
                    mock(PluginDataCleanup.class),
                    mock(RequestContinuationStore.class));

    @Test
    void tenantFilesystemFailureDoesNotRevealHostMapping(@TempDir @NonNull Path base)
            throws Exception {
        configuration.setDeployerPolicy(DeployerPolicy.TENANT);
        configuration.getTenant().setRoots(List.of(base.toString()));
        when(protectedSets.resolve(any(), anyString(), any()))
                .thenReturn(new ProtectedSet(Set.of()));
        var ownerRoot = Files.createDirectory(base.resolve("alice"));
        Files.writeString(ownerRoot.resolve("project"), "existing file");
        var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.createSession("alice", "coder", null, "/0/project"));
        var message = failure.getMessage();
        if (message == null) throw new AssertionError("missing failure message");
        assertTrue(message.contains("/0/project"));
        assertFalse(message.contains(base.toString()));
    }

    @BeforeEach
    void persistence() {
        when(sessions.claimedRootsExcept(anyString())).thenCallRealMethod();
        when(patterns.findByNameAndOwner(eq("coder"), anyString()))
                .thenAnswer(
                        call ->
                                Optional.of(
                                        new AgentPatternEntity(
                                                "coder",
                                                "DEEPSEEK",
                                                "model",
                                                "prompt",
                                                call.getArgument(1))));
        when(tiers.resolve(anyString(), any()))
                .thenReturn(
                        new ModelBinding(
                                ProviderType.DEEPSEEK, "model", "profile", 0.7, 4096, null));
        when(sessions.save(any(SessionEntity.class))).thenAnswer(call -> call.getArgument(0));
        when(agents.save(any(AgentEntity.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void directoryCreationRejectsOtherOwnersClaimAndUnsafeNames(@TempDir @NonNull Path tempDir)
            throws IOException {
        var occupied = tempDir.resolve("occupied");
        when(sessions.findByOwnerNot("alice"))
                .thenReturn(List.of(new SessionEntity("bob", "existing", occupied.toString())));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.createWorkspaceDirectory("alice", tempDir, "occupied"));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.createWorkspaceDirectory("alice", tempDir, "../escaped"));
        assertFalse(Files.exists(occupied));
        var created = service.createWorkspaceDirectory("alice", tempDir, "available");
        assertEquals(tempDir.resolve("available").toRealPath(), created);
        assertTrue(Files.isDirectory(created));
        verify(sessions, never()).save(any(SessionEntity.class));
    }

    @Test
    void anotherOwnerCannotClaimEqualParentOrChildRoots(@TempDir @NonNull Path tempDir) {
        var existingRoot = tempDir.resolve("occupied");
        when(sessions.findByOwnerNot("alice"))
                .thenReturn(List.of(new SessionEntity("bob", "existing", existingRoot.toString())));
        for (var root : List.of(existingRoot, tempDir, existingRoot.resolve("child"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.createSession("alice", "coder", null, root.toString()));
        }
        assertFalse(Files.exists(existingRoot));
        verify(sessions, never()).save(any(SessionEntity.class));
        verifyNoInteractions(agents);
    }

    @Test
    void ownerCanReuseWorkspaceAndDefaultsSelectNoPlugins(@TempDir @NonNull Path tempDir) {
        var root = tempDir.resolve("project");
        var first = service.createSession("alice", "coder", null, root.toString());
        var second = service.createSession("alice", "coder", null, root.toString());
        assertEquals(first.getWorkspaceRoots(), second.getWorkspaceRoots());
        assertTrue(Files.isDirectory(root));
        assertEquals(List.of(), second.getPluginBindings());
        verify(plugins, times(2)).selection(List.of());
        verify(sessions, times(2)).findByOwnerNot("alice");
    }

    @Test
    void invalidPluginSelectionDoesNotCreateDirectories(@TempDir @NonNull Path tempDir) {
        var root = tempDir.resolve("project");
        when(plugins.selection(anyList()))
                .thenThrow(new IllegalArgumentException("unavailable plugin"));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.createSession("alice", "coder", null, root.toString()));
        assertFalse(Files.exists(root));
        verify(sessions, never()).save(any(SessionEntity.class));
        verifyNoInteractions(agents);
    }

    @Test
    void symlinkAliasCannotBypassAnotherOwnersClaim(@TempDir @NonNull Path tempDir)
            throws Exception {
        var existingRoot = Files.createDirectory(tempDir.resolve("occupied"));
        var alias = tempDir.resolve("alias");
        try {
            Files.createSymbolicLink(alias, existingRoot);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(
                    false, "host does not permit directory symlinks: " + e.getMessage());
        }
        when(sessions.findByOwnerNot("alice"))
                .thenReturn(List.of(new SessionEntity("bob", "existing", existingRoot.toString())));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.createSession(
                                "alice", "coder", null, alias.resolve("child").toString()));
        assertFalse(Files.exists(existingRoot.resolve("child")));
        verify(sessions, never()).save(any(SessionEntity.class));
    }

    @Test
    void unspecifiedPersistedRootReservesBackendWorkingDirectory() {
        when(sessions.findByOwnerNot("alice"))
                .thenReturn(List.of(new SessionEntity("bob", "existing")));
        assertThrows(IllegalArgumentException.class, () -> service.createSession("alice", "coder"));
        verify(sessions, never()).save(any(SessionEntity.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentClaimWaitsForCommitOrRollback(boolean committed, @TempDir @NonNull Path tempDir)
            throws Exception {
        var root = tempDir.resolve("project");
        var persisted = new AtomicReference<SessionEntity>();
        when(sessions.findByOwnerNot(anyString()))
                .thenAnswer(
                        call -> {
                            var claim = persisted.get();
                            return claim == null || claim.getOwner().equals(call.getArgument(0))
                                    ? List.of()
                                    : List.of(claim);
                        });
        var secondValidated = new CountDownLatch(1);
        when(tiers.resolve(eq("bob"), any()))
                .thenAnswer(
                        call -> {
                            secondValidated.countDown();
                            return new ModelBinding(
                                    ProviderType.DEEPSEEK, "model", "profile", 0.7, 4096, null);
                        });
        var executor = Executors.newSingleThreadExecutor();
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            var first = service.createSession("alice", "coder", null, root.toString());
            var second =
                    executor.submit(
                            () -> service.createSession("bob", "coder", null, root.toString()));
            assertTrue(secondValidated.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
            if (committed) persisted.set(first);
            for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(
                        committed
                                ? TransactionSynchronization.STATUS_COMMITTED
                                : TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            TransactionSynchronizationManager.clear();
            if (committed) {
                var failure =
                        assertThrows(
                                ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
                assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            } else {
                assertEquals("bob", second.get(5, TimeUnit.SECONDS).getOwner());
            }
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                for (var synchronization :
                        TransactionSynchronizationManager.getSynchronizations()) {
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
                }
            }
            TransactionSynchronizationManager.clear();
            assertTrue(executor.shutdownNow().isEmpty());
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
