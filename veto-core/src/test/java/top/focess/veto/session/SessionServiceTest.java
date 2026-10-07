package top.focess.veto.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.TurnType;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.agent.continuation.RequestContinuationStore;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.intercept.HitlRecordRepository;
import top.focess.veto.agent.screening.DeployerPolicyConfiguration;
import top.focess.veto.agent.screening.ProtectedSetResolver;
import top.focess.veto.agent.workspace.WorkspaceAdmissionPolicy;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.event.SessionDeletedEvent;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.bus.SessionInvalidations;
import top.focess.veto.event.EventManager;
import top.focess.veto.integration.plugins.PluginDataCleanup;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.PluginTestSupport;
import top.focess.veto.integration.plugins.storage.ScopedPluginStorage;
import top.focess.veto.memory.TurnRecordRepository;
import top.focess.veto.model.AgentEntity;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.vault.TestUsers;
import top.focess.veto.vault.UserEntity;
import top.focess.veto.vault.UserRegistry;

class SessionServiceTest {
    private final @NonNull SessionAgentRegistry liveAgents = mock(SessionAgentRegistry.class);

    @Test
    void sessionDeletionStopsIndependentPeerAfterPrimaryAlreadyTerminated() {
        var sessions = mock(SessionRepository.class);
        var session = new SessionEntity(TestUsers.ALICE, "coder");
        var sessionId = UUID.fromString(session.getId());
        when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of(session));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        try (var registry =
                new SessionAgentRegistry(
                        mock(AgentInstanceRepository.class),
                        mock(TurnRecordRepository.class),
                        mock(SessionInvalidations.class))) {
            var primary = mock(VetoAgent.class);
            when(primary.id()).thenReturn("primary");
            when(primary.name()).thenReturn("primary");
            when(primary.persona())
                    .thenReturn(
                            new AgentPersona(
                                    "primary", "primary", "Test agent", Set.of(), Role.STANDALONE));
            when(primary.state()).thenReturn(AgentState.IDLE);
            var peer = mock(VetoAgent.class);
            when(peer.id()).thenReturn("peer");
            when(peer.name()).thenReturn("peer");
            when(peer.persona())
                    .thenReturn(
                            new AgentPersona(
                                    "peer", "peer", "Test agent", Set.of(), Role.STANDALONE));
            when(peer.state()).thenReturn(AgentState.IDLE);
            registry.register(sessionId, primary);
            registry.register(sessionId, peer);
            registry.stop("primary");
            assertEquals(1, registry.agents(sessionId).size());
            var service =
                    new SessionService(
                            sessions,
                            mock(AgentInstanceRepository.class),
                            mock(AgentPatternRepository.class),
                            mock(AgentService.class),
                            registry,
                            mock(SessionHistoryLoader.class),
                            mock(ModelTierRegistry.class),
                            new WorkspaceAdmissionPolicy(
                                    new DeployerPolicyConfiguration(),
                                    mock(ProtectedSetResolver.class),
                                    TestUsers.registry()),
                            mock(ScopedPluginStorage.class),
                            mock(HitlRecordRepository.class),
                            mock(PluginManager.class),
                            mock(EventManager.class),
                            mock(PluginDataCleanup.class),
                            mock(RequestContinuationStore.class));
            assertTrue(service.delete(TestUsers.ALICE, session.getId()));
            assertTrue(registry.agents(sessionId).isEmpty());
            verify(peer).terminate();
        }
    }

    @Test
    void rolledBackSessionDeletionDoesNotPublishDeletionOrStopItsAgent() {
        var sessions = mock(SessionRepository.class);
        var agentService = mock(AgentService.class);
        var session = new SessionEntity(TestUsers.ALICE, "coder");
        when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of(session));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        var events = mock(EventManager.class);
        var service =
                new SessionService(
                        sessions,
                        mock(AgentInstanceRepository.class),
                        mock(AgentPatternRepository.class),
                        agentService,
                        liveAgents,
                        mock(SessionHistoryLoader.class),
                        mock(ModelTierRegistry.class),
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        events,
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertTrue(service.delete(TestUsers.ALICE, session.getId()));
            verifyNoInteractions(events);
            verify(liveAgents, never()).stopSession(UUID.fromString(session.getId()));
            for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            verifyNoInteractions(events);
            verify(liveAgents, never()).stopSession(UUID.fromString(session.getId()));
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void monitorActivationUsesExactIdentityAndRejectsMissingRecoveryEvidence() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService runtime = mock(AgentService.class);
        SessionHistoryLoader history = mock(SessionHistoryLoader.class);
        var session = new SessionEntity(TestUsers.ALICE, "duplicate-name");
        var primary =
                new AgentEntity(
                        session.getId(),
                        null,
                        AgentEntity.Role.PRIMARY,
                        "Primary",
                        "DEEPSEEK",
                        "model",
                        "key");
        session.setPrimaryAgentId(primary.getId());
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(agents.findById(primary.getId())).thenReturn(Optional.of(primary));
        var replay = List.of(TurnRecord.userPrompt(1, "Original task"));
        when(history.load(session.getId(), primary.getId())).thenReturn(replay);
        UUID user = TestUsers.ALICE;
        var service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        runtime,
                        liveAgents,
                        history,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));
        UUID id = UUID.fromString(session.getId());
        assertFalse(service.activateForObservation(id, TestUsers.BOB, primary.getId()));
        assertFalse(service.activateForObservation(id, TestUsers.ALICE, primary.getId()));
        ReflectionTestUtils.setField(primary, "recoveryVersion", 1);
        assertFalse(service.activateForObservation(id, TestUsers.ALICE, primary.getId()));
        replay =
                List.of(
                        TurnRecord.userPrompt(1, "Original task"),
                        new TurnRecord(
                                2, TurnType.ASSISTANT_RESPONSE, Map.of("content", "Done"), null));
        when(history.load(session.getId(), primary.getId())).thenReturn(replay);
        var reader = AgentEntity.spawned("reader", session.getId(), "Reader");
        ReflectionTestUtils.setField(reader, "recoveryVersion", 1);
        ReflectionTestUtils.setField(reader, "runtimeRole", "STANDALONE");
        ReflectionTestUtils.setField(reader, "parentCallId", "read-call");
        when(agents.findById("reader")).thenReturn(Optional.of(reader));
        assertFalse(service.activateForObservation(id, TestUsers.ALICE, "reader"));
        var livePrimary = mock(VetoAgent.class);
        when(livePrimary.id()).thenReturn(primary.getId());
        doAnswer(
                        invocation -> {
                            when(liveAgents.agents(id))
                                    .thenReturn(
                                            List.of(
                                                    new SessionAgentRegistry.Entry(
                                                            id, null, null, livePrimary)));
                            return livePrimary;
                        })
                .when(runtime)
                .getOrCreateAgent(
                        anyString(), anyString(), any(), anyList(), any(), any(), anyInt(), any());
        assertTrue(service.activateForObservation(id, TestUsers.ALICE, primary.getId()));
        verify(runtime)
                .getOrCreateAgent(
                        eq(session.getId()),
                        eq(primary.getId()),
                        any(),
                        eq(replay),
                        eq(user),
                        any(),
                        anyInt(),
                        any());

        // An inactive plugin mate remains pending; restoring only its primary is not success.
        var mate = AgentEntity.spawned("mate", session.getId(), "Mate");
        ReflectionTestUtils.setField(mate, "recoveryVersion", 1);
        ReflectionTestUtils.setField(mate, "runtimeRole", "MATE");
        when(agents.findById(mate.getId())).thenReturn(Optional.of(mate));
        when(history.load(session.getId(), mate.getId())).thenReturn(replay);
        clearInvocations(runtime);
        assertFalse(service.activateForObservation(id, user, mate.getId()));
        assertFalse(service.activateForObservation(id, user, mate.getId()));
        verify(runtime, times(2))
                .getOrCreateAgent(
                        anyString(), anyString(), any(), anyList(), any(), any(), anyInt(), any());

        // Once plugin-owned recovery supplies the exact mate, the next retry can deliver work.
        var liveMate = mock(VetoAgent.class);
        when(liveMate.id()).thenReturn(mate.getId());
        when(liveAgents.agents(id))
                .thenReturn(
                        List.of(
                                new SessionAgentRegistry.Entry(id, null, null, livePrimary),
                                new SessionAgentRegistry.Entry(
                                        id, primary.getId(), null, liveMate)));
        clearInvocations(runtime);
        assertTrue(service.activateForObservation(id, user, mate.getId()));
        verifyNoInteractions(runtime);
        verify(agents, never()).save(any());
        verify(agents, never()).delete(any());
        verify(sessions, never())
                .findFirstByNameAndUserIdOrderByLastActiveAtDesc(anyString(), any(UUID.class));
        verify(sessions, never()).save(any());
    }

    private final @NonNull ModelTierRegistry tierRegistry = mock(ModelTierRegistry.class);

    /**
     * A stable cwd used by the terminal-side tests. Matches sessions whose workspaceRoots is null
     * (treated as "any workspace" for legacy data) — so all the historical "happy path" tests pass
     * through {@code isInWorkspace} without changes.
     */
    private static final @NonNull String CWD =
            Objects.requireNonNull(System.getProperty("user.dir"), "user.dir");

    @BeforeEach
    void stubResolve() {
        // SessionService resolves the userId's tier to a concrete binding at create + activate; the
        // mock stands in for the per-user registry (no JPA needed for these unit tests).
        when(tierRegistry.resolve(any(UUID.class), any()))
                .thenReturn(
                        new ModelBinding(
                                ProviderType.DEEPSEEK,
                                "deepseek-chat",
                                "deepseek-default",
                                0.7,
                                4096,
                                null));
    }

    @Test
    void createSessionBuildsPrimaryAgent() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        AgentPatternEntity pattern =
                new AgentPatternEntity(
                        "coder", "DEEPSEEK", "deepseek-v4", "pattern-coder", TestUsers.ALICE);
        when(patterns.findByNameAndUserId("coder", TestUsers.ALICE))
                .thenReturn(Optional.of(pattern));
        when(sessions.findByUserIdAndNameAndWorkspaceRoots(
                        any(UUID.class), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(sessions.save(any(SessionEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(agents.save(any(AgentEntity.class))).thenAnswer(i -> i.getArgument(0));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        SessionEntity session = service.createSession(TestUsers.ALICE, "coder");
        assertEquals(ToolResultPresentationMode.BASIC, session.getToolResultPresentation());
        assertEquals(List.of(), session.getPluginBindings());
        requirePrimaryAgentId(session, "primary agent created and linked");
        verify(agents).save(any(AgentEntity.class));
    }

    @Test
    void createSessionWithCustomName() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        AgentPatternEntity pattern =
                new AgentPatternEntity(
                        "coder", "DEEPSEEK", "deepseek-v4", "pattern-coder", TestUsers.ALICE);
        when(patterns.findByNameAndUserId("coder", TestUsers.ALICE))
                .thenReturn(Optional.of(pattern));
        when(sessions.findByUserIdAndNameAndWorkspaceRoots(
                        any(UUID.class), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(sessions.save(any(SessionEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(agents.save(any(AgentEntity.class))).thenAnswer(i -> i.getArgument(0));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        SessionEntity session =
                service.createSession(
                        TestUsers.ALICE,
                        "coder",
                        "mysession",
                        List.of(CWD),
                        ToolResultPresentationMode.DETAILED);
        assertEquals("mysession", session.getName());
        assertEquals(ToolResultPresentationMode.DETAILED, session.getToolResultPresentation());

        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessions, atLeastOnce()).save(captor.capture());
        assertEquals("mysession", captor.getAllValues().get(0).getName());
    }

    @Test
    void createSessionGeneratesUniqueNameFromPattern() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        AgentPatternEntity pattern =
                new AgentPatternEntity(
                        "coder", "DEEPSEEK", "deepseek-v4", "pattern-coder", TestUsers.ALICE);
        when(patterns.findByNameAndUserId("coder", TestUsers.ALICE))
                .thenReturn(Optional.of(pattern));
        when(sessions.findByUserIdAndNameAndWorkspaceRoots(
                        any(UUID.class), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(sessions.save(any(SessionEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(agents.save(any(AgentEntity.class))).thenAnswer(i -> i.getArgument(0));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        SessionEntity session = service.createSession(TestUsers.ALICE, "coder", null, List.of(CWD));
        assertTrue(
                session.getName().startsWith("coder-"),
                "an implicit session name must be derived from the pattern name");
        assertTrue(
                session.getName().matches("coder-[0-9a-f]{8}"),
                "generated name must be patternName-8hex, got: " + session.getName());
    }

    @Test
    void activateResolvesConfigFromPrimaryAgent() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        SessionEntity session = new SessionEntity(TestUsers.ALICE, "coder");
        AgentEntity agent =
                new AgentEntity(
                        session.getId(),
                        "pat-id",
                        AgentEntity.Role.PRIMARY,
                        "coder",
                        "DEEPSEEK",
                        "deepseek-v4",
                        "pattern-coder");
        session.setPrimaryAgentId(agent.getId());

        when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of(session));
        when(sessions.findFirstByNameAndUserIdOrderByLastActiveAtDesc("coder", TestUsers.ALICE))
                .thenReturn(Optional.of(session));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(session.getId(), agent.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(
                        anyString(), any(), any(), anyList(), any(), any(), anyInt(), any()))
                .thenReturn(mock(Agent.class));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        Optional<LlmConfig> cfg = service.activate("term-1", "coder", TestUsers.ALICE, CWD);
        assertTrue(cfg.isPresent());
        assertEquals(ProviderType.DEEPSEEK, cfg.get().provider());
        // The agent's tier (TOP) resolves live via the model-tier registry (mocked here).
        assertEquals("deepseek-chat", cfg.get().model());
        assertEquals(Integer.valueOf(128000), cfg.get().options().contextWindowTokens());
        assertEquals(Integer.valueOf(4096), cfg.get().options().maxTokens());
        assertEquals(Double.valueOf(0.7), cfg.get().options().temperature());
        assertEquals(Optional.of(session.getId()), service.activeSession("term-1"));
    }

    @Test
    void deactivateClearsActive() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        SessionEntity session = new SessionEntity(TestUsers.ALICE, "coder");
        AgentEntity agent =
                new AgentEntity(
                        session.getId(),
                        "pat-id",
                        AgentEntity.Role.PRIMARY,
                        "coder",
                        "DEEPSEEK",
                        "deepseek-v4",
                        "pattern-coder");
        session.setPrimaryAgentId(agent.getId());

        when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of(session));
        when(sessions.findFirstByNameAndUserIdOrderByLastActiveAtDesc("coder", TestUsers.ALICE))
                .thenReturn(Optional.of(session));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(session.getId(), agent.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(
                        anyString(), any(), any(), anyList(), any(), any(), anyInt(), any()))
                .thenReturn(mock(Agent.class));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));
        service.activate("term-1", "coder", TestUsers.ALICE, CWD);
        service.deactivate("term-1");
        assertTrue(service.activeSession("term-1").isEmpty());
    }

    @Test
    void deactivateUserDetachesUserTerminals() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        SessionEntity session = new SessionEntity(TestUsers.ALICE, "coder");
        AgentEntity agent =
                new AgentEntity(
                        session.getId(),
                        "pat-id",
                        AgentEntity.Role.PRIMARY,
                        "coder",
                        "DEEPSEEK",
                        "deepseek-v4",
                        "pattern-coder");
        session.setPrimaryAgentId(agent.getId());

        when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of(session));
        when(sessions.findFirstByNameAndUserIdOrderByLastActiveAtDesc("coder", TestUsers.ALICE))
                .thenReturn(Optional.of(session));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(session.getId(), agent.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(
                        anyString(), any(), any(), anyList(), any(), any(), anyInt(), any()))
                .thenReturn(mock(Agent.class));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));
        service.activate("term-1", "coder", TestUsers.ALICE, CWD);
        assertTrue(service.activeSession("term-1").isPresent());

        service.deactivateUser(TestUsers.ALICE);
        assertTrue(
                service.activeSession("term-1").isEmpty(),
                "unified logout detaches the user's terminals");
    }

    @Test
    void resumeLastSessionActivatesUserIdsMostRecent() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        SessionEntity session = new SessionEntity(TestUsers.ALICE, "coder");
        AgentEntity agent =
                new AgentEntity(
                        session.getId(),
                        "pat-id",
                        AgentEntity.Role.PRIMARY,
                        "coder",
                        "DEEPSEEK",
                        "deepseek-v4",
                        "pattern-coder");
        session.setPrimaryAgentId(agent.getId());

        when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of(session));
        when(sessions.findFirstByNameAndUserIdOrderByLastActiveAtDesc("coder", TestUsers.ALICE))
                .thenReturn(Optional.of(session));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(session.getId(), agent.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(
                        anyString(), any(), any(), anyList(), any(), any(), anyInt(), any()))
                .thenReturn(mock(Agent.class));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        Optional<LlmConfig> cfg = service.resumeLastSession("term-1", TestUsers.ALICE, CWD);
        assertTrue(cfg.isPresent(), "last session auto-resumed");
        assertEquals(Optional.of(session.getId()), service.activeSession("term-1"));
    }

    @Test
    void resumeLastSessionEmptyWhenUserIdHasNoSessions() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of());

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));
        assertTrue(service.resumeLastSession("term-1", TestUsers.ALICE, CWD).isEmpty());
        assertTrue(service.activeSession("term-1").isEmpty());
    }

    @Test
    void createSessionRejectsUnknownPattern() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        when(patterns.findByNameAndUserId("nope", TestUsers.ALICE)).thenReturn(Optional.empty());

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.createSession(TestUsers.ALICE, "nope"));
    }

    @Test
    void deleteCascadesAndDetachesTerminal() throws Exception {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        SessionEntity session = new SessionEntity(TestUsers.ALICE, "coder");
        AgentEntity agent =
                new AgentEntity(
                        session.getId(),
                        "pat-id",
                        AgentEntity.Role.PRIMARY,
                        "coder",
                        "DEEPSEEK",
                        "deepseek-v4",
                        "pattern-coder");
        session.setPrimaryAgentId(agent.getId());

        when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of(session));
        when(sessions.findFirstByNameAndUserIdOrderByLastActiveAtDesc("coder", TestUsers.ALICE))
                .thenReturn(Optional.of(session));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(session.getId(), agent.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(
                        anyString(), any(), any(), anyList(), any(), any(), anyInt(), any()))
                .thenReturn(mock(Agent.class));

        boolean removed;
        var scope = new Scope.AgentScope(TestUsers.ALICE, session.getId(), agent.getId());
        try (var plugins = PluginTestSupport.manager()) {
            var events = spy(PluginTestSupport.eventManager(plugins));
            var users = mock(UserRegistry.class);
            var user = mock(UserEntity.class);
            when(user.getUserId()).thenReturn(TestUsers.ALICE);
            when(users.findByUserId(TestUsers.ALICE)).thenReturn(Optional.of(user));
            var cleanup = new PluginDataCleanup(plugins, users);
            SessionService service =
                    new SessionService(
                            sessions,
                            agents,
                            patterns,
                            agentService,
                            liveAgents,
                            loader,
                            tierRegistry,
                            new WorkspaceAdmissionPolicy(
                                    new DeployerPolicyConfiguration(),
                                    mock(ProtectedSetResolver.class),
                                    TestUsers.registry()),
                            mock(ScopedPluginStorage.class),
                            mock(HitlRecordRepository.class),
                            mock(PluginManager.class),
                            events,
                            cleanup,
                            mock(RequestContinuationStore.class));
            service.activate("term-1", "coder", TestUsers.ALICE, CWD);
            assertTrue(service.activeSession("term-1").isPresent());
            String captured =
                    PluginTestSupport.protect(
                            plugins,
                            BeforeTextCommitEvent.Phase.INPUT,
                            scope,
                            "source",
                            "password=alpha");
            var matcher = java.util.regex.Pattern.compile("s_[a-f0-9]{32}").matcher(captured);
            assertTrue(matcher.find(), "expected a captured reference");
            String secret = matcher.group();
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                removed = service.delete(TestUsers.ALICE, session.getId());
                verify(events, never()).submit(any());
                var synchronizations = TransactionSynchronizationManager.getSynchronizations();
                for (var synchronization : synchronizations) synchronization.beforeCommit(false);
                for (var synchronization : synchronizations) synchronization.afterCommit();
                verify(events)
                        .submit(
                                argThat(
                                        event ->
                                                event instanceof SessionDeletedEvent deleted
                                                        && deleted.scope()
                                                                .equals(
                                                                        new Scope.SessionScope(
                                                                                TestUsers.ALICE,
                                                                                session.getId()))));
                for (var synchronization : synchronizations) {
                    synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
                }
            } finally {
                TransactionSynchronizationManager.clear();
            }
            assertTrue(service.activeSession("term-1").isEmpty(), "terminal detached on delete");
            assertTrue(PluginTestSupport.reveal(plugins, scope, secret).isEmpty());
            // The retired session cannot capture new references. Its listener failure is
            // contained by event delivery, leaving the submitted text unchanged.
            assertEquals(
                    "password=alpha",
                    PluginTestSupport.protect(
                            plugins,
                            BeforeTextCommitEvent.Phase.INPUT,
                            scope,
                            "late",
                            "password=alpha"));
        }
        assertTrue(removed, "delete should report the session removed");

        verify(liveAgents).stopSession(UUID.fromString(session.getId()));
        verify(agents).deleteBySessionId(session.getId());
        verify(sessions).delete(session);
    }

    @Test
    void deleteReturnsFalseForUnknownSession() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of());
        when(sessions.findFirstByNameAndUserIdOrderByLastActiveAtDesc("nope", TestUsers.ALICE))
                .thenReturn(Optional.empty());

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));
        assertFalse(service.delete(TestUsers.ALICE, "nope"));
        verify(liveAgents, never()).stopSession(any());
    }

    @Test
    void activateSeedsReplayedHistoryIntoAgent() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        SessionEntity session =
                new SessionEntity(
                        TestUsers.ALICE,
                        "coder",
                        CWD + "," + fakeDir("selected-root"),
                        1,
                        ToolResultPresentationMode.BASIC);
        AgentEntity agent =
                new AgentEntity(
                        session.getId(),
                        "pat-id",
                        AgentEntity.Role.PRIMARY,
                        "coder",
                        "DEEPSEEK",
                        "deepseek-v4",
                        "pattern-coder");
        session.setPrimaryAgentId(agent.getId());

        List<TurnRecord> history =
                List.of(
                        TurnRecord.userPrompt(1, "earlier prompt"),
                        TurnRecord.assistantResponse(2, "earlier reply"));
        when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of(session));
        when(sessions.findFirstByNameAndUserIdOrderByLastActiveAtDesc("coder", TestUsers.ALICE))
                .thenReturn(Optional.of(session));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(session.getId(), agent.getId())).thenReturn(history);
        when(agentService.getOrCreateAgent(
                        anyString(), any(), any(), anyList(), any(), any(), anyInt(), any()))
                .thenReturn(mock(Agent.class));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));
        service.activate("term-1", "coder", TestUsers.ALICE, CWD);

        // The replayed history loaded from the durable log is threaded into getOrCreateAgent so the
        // agent seeds it on first creation (idempotent across re-activates and resume).
        verify(agentService)
                .getOrCreateAgent(
                        eq(session.getId()),
                        eq(agent.getId()),
                        any(),
                        eq(history),
                        any(),
                        any(),
                        eq(1),
                        eq(ToolResultPresentationMode.BASIC));
    }

    @Test
    void createSessionPersistsAnExplicitCurrentWorkspaceRoot() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        AgentPatternEntity pattern =
                new AgentPatternEntity(
                        "coder", "DEEPSEEK", "deepseek-v4", "pattern-coder", TestUsers.ALICE);
        var roots = List.of(fakeDir("root-a"), fakeDir("root-b"));
        when(patterns.findByNameAndUserId("coder", TestUsers.ALICE))
                .thenReturn(Optional.of(pattern));
        when(sessions.findByUserIdAndNameAndWorkspaceRoots(
                        TestUsers.ALICE, "selected", String.join(",", roots)))
                .thenReturn(Optional.empty());
        when(sessions.save(any(SessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(agents.save(any(AgentEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        SessionEntity created =
                service.createSession(
                        TestUsers.ALICE,
                        "coder",
                        "selected",
                        roots,
                        1,
                        ToolResultPresentationMode.BASIC);

        assertEquals(1, created.getCurrentWorkspaceRootIndex());
    }

    @Test
    void createSessionRejectsAnOutOfRangeCurrentWorkspaceRoot() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        AgentPatternEntity pattern =
                new AgentPatternEntity(
                        "coder", "DEEPSEEK", "deepseek-v4", "pattern-coder", TestUsers.ALICE);
        String roots = fakeDir("only-root");
        when(patterns.findByNameAndUserId("coder", TestUsers.ALICE))
                .thenReturn(Optional.of(pattern));
        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                service.createSession(
                                        TestUsers.ALICE,
                                        "coder",
                                        "invalid-root",
                                        List.of(roots),
                                        1,
                                        ToolResultPresentationMode.BASIC));

        assertTrue(String.valueOf(failure.getMessage()).contains("currentWorkspaceRootIndex"));
        verify(sessions, never()).save(any(SessionEntity.class));
    }

    // ── workspace binding ─────────────────────────────────────────────────

    /** Synthetic terminal cwd rooted under the JVM tmp dir; deterministic across runs. */
    private static @NonNull String fakeDir(@NonNull String name) {
        return Path.of(
                        Objects.requireNonNull(
                                System.getProperty("java.io.tmpdir"), "java.io.tmpdir"),
                        name)
                .toAbsolutePath()
                .normalize()
                .toString();
    }

    private static @NonNull String requirePrimaryAgentId(
            @NonNull SessionEntity session, @NonNull String message) {
        String primaryAgentId = session.getPrimaryAgentId();
        if (primaryAgentId == null) {
            throw new AssertionError(message);
        }
        return primaryAgentId;
    }

    @Test
    void listSessionsScopedToCwdReturnsOnlyInWorkspaceSessions() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        String projectA = fakeDir("veto-test-ws-A");
        String projectB = fakeDir("veto-test-ws-B");
        String projectASub = fakeDir("veto-test-ws-A/sub");

        SessionEntity inA = new SessionEntity(TestUsers.ALICE, "alpha", projectA);
        SessionEntity inB = new SessionEntity(TestUsers.ALICE, "beta", projectB);
        SessionEntity legacy = new SessionEntity(TestUsers.ALICE, "legacy", null); // no binding

        when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of(inA, inB, legacy));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        // Terminal in projectA: sees inA (exact) + legacy (null = any) — NOT inB.
        List<SessionEntity> seenInA = service.listSessions(TestUsers.ALICE, projectA);
        assertEquals(2, seenInA.size());
        assertTrue(seenInA.contains(inA));
        assertTrue(seenInA.contains(legacy));
        assertFalse(seenInA.contains(inB));

        // Terminal in a subdirectory of projectA: still in-scope of inA.
        List<SessionEntity> seenInASub = service.listSessions(TestUsers.ALICE, projectASub);
        assertEquals(2, seenInASub.size());
        assertTrue(seenInASub.contains(inA));
        assertFalse(seenInASub.contains(inB));

        // Terminal in projectB: sees inB + legacy — NOT inA.
        List<SessionEntity> seenInB = service.listSessions(TestUsers.ALICE, projectB);
        assertEquals(2, seenInB.size());
        assertTrue(seenInB.contains(inB));
        assertTrue(seenInB.contains(legacy));
        assertFalse(seenInB.contains(inA));
    }

    @Test
    void terminalWorkspaceAliasesResolveForListActivationAndResume(@TempDir @NonNull Path temp)
            throws IOException, InterruptedException {
        var root = Files.createDirectory(temp.resolve("workspace")).toRealPath();
        var alias = temp.resolve("alias");
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            var process =
                    new ProcessBuilder(
                                    "cmd", "/c", "mklink", "/J", alias.toString(), root.toString())
                            .redirectErrorStream(true)
                            .start();
            var output = new String(process.getInputStream().readAllBytes());
            assertEquals(0, process.waitFor(), output);
        } else {
            Files.createSymbolicLink(alias, root);
        }
        try {
            var sessions = mock(SessionRepository.class);
            var session = new SessionEntity(TestUsers.ALICE, "linked", root.toString());
            when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of(session));
            var service =
                    new SessionService(
                            sessions,
                            mock(AgentInstanceRepository.class),
                            mock(AgentPatternRepository.class),
                            mock(AgentService.class),
                            liveAgents,
                            mock(SessionHistoryLoader.class),
                            tierRegistry,
                            new WorkspaceAdmissionPolicy(
                                    new DeployerPolicyConfiguration(),
                                    mock(ProtectedSetResolver.class),
                                    TestUsers.registry()),
                            mock(ScopedPluginStorage.class),
                            mock(HitlRecordRepository.class),
                            mock(PluginManager.class),
                            mock(EventManager.class),
                            mock(PluginDataCleanup.class),
                            mock(RequestContinuationStore.class));
            assertEquals(List.of(session), service.listSessions(TestUsers.ALICE, alias.toString()));
            // A session without its primary agent resolves its workspace, then returns empty.
            assertTrue(
                    service.activate("terminal", "linked", TestUsers.ALICE, alias.toString())
                            .isEmpty());
            assertTrue(
                    service.resumeLastSession("terminal", TestUsers.ALICE, alias.toString())
                            .isEmpty());
        } finally {
            Files.delete(alias);
        }
    }

    @Test
    void listSessionsUnscopedReturnsAll() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        SessionEntity a = new SessionEntity(TestUsers.ALICE, "alpha", fakeDir("ws-A"));
        SessionEntity b = new SessionEntity(TestUsers.ALICE, "beta", fakeDir("ws-B"));
        when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of(a, b));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        // Single-arg REST-style list returns every session regardless of binding.
        assertEquals(2, service.listSessions(TestUsers.ALICE).size());
    }

    @Test
    void activateRejectsOutOfWorkspaceCwd() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        String projectA = fakeDir("veto-test-ws-A");
        String projectB = fakeDir("veto-test-ws-B");
        SessionEntity session = new SessionEntity(TestUsers.ALICE, "alpha", projectA);
        when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of(session));
        when(sessions.findFirstByNameAndUserIdOrderByLastActiveAtDesc("alpha", TestUsers.ALICE))
                .thenReturn(Optional.of(session));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.activate("term-1", "alpha", TestUsers.ALICE, projectB));
        assertTrue(
                String.valueOf(ex.getMessage()).contains("alpha"),
                "error names the session so the user can identify it");
        assertTrue(
                String.valueOf(ex.getMessage()).contains(projectA),
                "error names the session's bound workspace so the user knows where to cd");
        assertTrue(
                String.valueOf(ex.getMessage()).contains(projectB),
                "error names the current cwd so the user can see the mismatch");
        // Strict binding: even when the session is found, the terminal is not attached.
        assertTrue(service.activeSession("term-1").isEmpty());
    }

    @Test
    void activateAcceptsCwdInsideWorkspaceRoot() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        String projectA = fakeDir("veto-test-ws-A");
        String projectASub = fakeDir("veto-test-ws-A/inner");
        SessionEntity session = new SessionEntity(TestUsers.ALICE, "alpha", projectA);
        AgentEntity agent =
                new AgentEntity(
                        session.getId(),
                        "pat-id",
                        AgentEntity.Role.PRIMARY,
                        "alpha",
                        "DEEPSEEK",
                        "deepseek-v4",
                        "pattern-alpha");
        session.setPrimaryAgentId(agent.getId());

        when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of(session));
        when(sessions.findFirstByNameAndUserIdOrderByLastActiveAtDesc("alpha", TestUsers.ALICE))
                .thenReturn(Optional.of(session));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(session.getId(), agent.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(
                        anyString(), any(), any(), anyList(), any(), any(), anyInt(), any()))
                .thenReturn(mock(Agent.class));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        // Subdirectory of the bound workspace: still in-scope.
        Optional<LlmConfig> cfg = service.activate("term-1", "alpha", TestUsers.ALICE, projectASub);
        assertTrue(cfg.isPresent(), "subdirectory of bound workspace activates cleanly");
    }

    @Test
    void resumeLastSessionSkipsOutOfWorkspaceSessions() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        String projectA = fakeDir("veto-test-ws-A");
        String projectB = fakeDir("veto-test-ws-B");
        // The user's only session is in projectA; the terminal just opened in projectB.
        // (The session is the most-recent overall — the case where a naive
        // findFirstByUserIdOrderByLastActiveAtDesc would silently resume into it.)
        SessionEntity inA = new SessionEntity(TestUsers.ALICE, "alpha", projectA);
        try {
            Field f = SessionEntity.class.getDeclaredField("lastActiveAt");
            f.setAccessible(true);
            f.set(inA, Instant.now());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of(inA));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        Optional<LlmConfig> cfg = service.resumeLastSession("term-1", TestUsers.ALICE, projectB);
        assertTrue(
                cfg.isEmpty(),
                "auto-resume must NOT silently resume into a session bound to a different"
                        + " workspace; the terminal sees 'No active session in this workspace'");
        assertTrue(service.activeSession("term-1").isEmpty());
    }

    @Test
    void resumeLastSessionPicksMostRecentInWorkspace() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        String projectA = fakeDir("veto-test-ws-A");
        // Two sessions in projectA; the newer one is alpha, the older one is zulu.
        SessionEntity older = new SessionEntity(TestUsers.ALICE, "zulu", projectA);
        try {
            Field f = SessionEntity.class.getDeclaredField("lastActiveAt");
            f.setAccessible(true);
            f.set(older, Instant.now().minusSeconds(60));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        SessionEntity newer = new SessionEntity(TestUsers.ALICE, "alpha", projectA);
        try {
            Field f = SessionEntity.class.getDeclaredField("lastActiveAt");
            f.setAccessible(true);
            f.set(newer, Instant.now());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        AgentEntity agent =
                new AgentEntity(
                        newer.getId(),
                        "pat-id",
                        AgentEntity.Role.PRIMARY,
                        "alpha",
                        "DEEPSEEK",
                        "deepseek-v4",
                        "pattern-alpha");
        newer.setPrimaryAgentId(agent.getId());

        when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of(older, newer));
        when(sessions.findFirstByNameAndUserIdOrderByLastActiveAtDesc("alpha", TestUsers.ALICE))
                .thenReturn(Optional.of(newer));
        when(sessions.findById(newer.getId())).thenReturn(Optional.of(newer));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(newer.getId(), agent.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(
                        anyString(), any(), any(), anyList(), any(), any(), anyInt(), any()))
                .thenReturn(mock(Agent.class));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        Optional<LlmConfig> cfg = service.resumeLastSession("term-1", TestUsers.ALICE, projectA);
        assertTrue(cfg.isPresent());
        assertEquals(
                Optional.of(newer.getId()),
                service.activeSession("term-1"),
                "most-recent in-workspace session wins, not the older one");
    }

    // ── workspace-scoped uniqueness ──────────────────────────────────────

    @Test
    void createSessionAllowsSameNameInDifferentWorkspace() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        String projectA = fakeDir("ws-A");
        String projectB = fakeDir("ws-B");
        AgentPatternEntity pattern =
                new AgentPatternEntity(
                        "coder", "DEEPSEEK", "deepseek-v4", "pattern-coder", TestUsers.ALICE);
        when(patterns.findByNameAndUserId("coder", TestUsers.ALICE))
                .thenReturn(Optional.of(pattern));
        // No row with (alice, "coder", projectB) yet — the row for (alice, "coder", projectA) is
        // in a different workspace, so it doesn't match this exact (name, workspaceRoots) lookup.
        when(sessions.findByUserIdAndNameAndWorkspaceRoots(
                        any(UUID.class), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(sessions.save(any(SessionEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(agents.save(any(AgentEntity.class))).thenAnswer(i -> i.getArgument(0));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        SessionEntity created =
                service.createSession(TestUsers.ALICE, "coder", null, List.of(projectB));
        assertTrue(
                created.getName().startsWith("coder-"),
                "implicit name must use the pattern as a prefix even in a different workspace");
        assertTrue(
                created.getName().matches("coder-[0-9a-f]{8}"),
                "generated name must be patternName-8hex, got: " + created.getName());
        assertEquals(projectB, created.getWorkspaceRoots());

        // The new row's workspace is projectB, NOT projectA — same name is fine in a different
        // workspace. createSession saves twice: first to get the generated id, then again to
        // persist the primaryAgentId once the agent row is built. Both saves carry projectB.
        ArgumentCaptor<SessionEntity> captor = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessions, times(2)).save(captor.capture());
        assertTrue(
                captor.getAllValues().stream()
                        .allMatch(s -> projectB.equals(s.getWorkspaceRoots())),
                "every persisted session row must carry the new workspace, not projectA");
        requirePrimaryAgentId(
                created, "createSession must persist the primaryAgentId on the second save");
    }

    @Test
    void createSessionRejectsSameNameInSameWorkspace() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        String projectA = fakeDir("ws-A");
        AgentPatternEntity pattern =
                new AgentPatternEntity(
                        "coder", "DEEPSEEK", "deepseek-v4", "pattern-coder", TestUsers.ALICE);
        SessionEntity existing = new SessionEntity(TestUsers.ALICE, "ds", projectA);
        when(patterns.findByNameAndUserId("coder", TestUsers.ALICE))
                .thenReturn(Optional.of(pattern));
        when(sessions.findByUserIdAndNameAndWorkspaceRoots(TestUsers.ALICE, "ds", projectA))
                .thenReturn(Optional.of(existing));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                service.createSession(
                                        TestUsers.ALICE, "coder", "ds", List.of(projectA)));
        assertTrue(
                String.valueOf(ex.getMessage()).contains("ds"),
                "error names the session so the user can identify it");
        assertTrue(
                String.valueOf(ex.getMessage()).contains(projectA),
                "error names the workspace so the user knows which one conflicts");
        verify(sessions, never()).save(any(SessionEntity.class));
    }

    @Test
    void createSessionImplicitNameSucceedsWhenPatternNameTaken() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        String projectA = fakeDir("ws-A");
        AgentPatternEntity pattern =
                new AgentPatternEntity(
                        "coder", "DEEPSEEK", "deepseek-v4", "pattern-coder", TestUsers.ALICE);
        SessionEntity existing = new SessionEntity(TestUsers.ALICE, "coder", projectA);
        when(patterns.findByNameAndUserId("coder", TestUsers.ALICE))
                .thenReturn(Optional.of(pattern));
        // The bare pattern name 'coder' is already taken in this workspace, but any generated
        // name 'coder-xxxxxxxx' is free - so /session create coder (no explicit name) succeeds.
        when(sessions.findByUserIdAndNameAndWorkspaceRoots(TestUsers.ALICE, "coder", projectA))
                .thenReturn(Optional.of(existing));
        when(sessions.findByUserIdAndNameAndWorkspaceRoots(
                        eq(TestUsers.ALICE),
                        argThat(name -> name != null && name.startsWith("coder-")),
                        eq(projectA)))
                .thenReturn(Optional.empty());
        when(sessions.save(any(SessionEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(agents.save(any(AgentEntity.class))).thenAnswer(i -> i.getArgument(0));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        SessionEntity created =
                service.createSession(TestUsers.ALICE, "coder", null, List.of(projectA));
        assertTrue(
                created.getName().matches("coder-[0-9a-f]{8}"),
                "must generate coder-xxxxxxxx when bare 'coder' is taken, got: "
                        + created.getName());
        assertEquals(projectA, created.getWorkspaceRoots());
    }

    @Test
    void activatePicksExplicitWorkspaceOverLegacyNull() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        String projectA = fakeDir("ws-A");
        // Two "ds" sessions for alice: one legacy (NULL = matches any cwd), one explicitly bound
        // to projectA. A terminal in projectA should activate the explicit one, not the legacy.
        SessionEntity legacy = new SessionEntity(TestUsers.ALICE, "ds", null);
        SessionEntity explicit = new SessionEntity(TestUsers.ALICE, "ds", projectA);
        AgentEntity agent =
                new AgentEntity(
                        explicit.getId(),
                        "pat-id",
                        AgentEntity.Role.PRIMARY,
                        "ds",
                        "DEEPSEEK",
                        "deepseek-v4",
                        "pattern-ds");
        explicit.setPrimaryAgentId(agent.getId());

        when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of(legacy, explicit));
        when(sessions.findFirstByNameAndUserIdOrderByLastActiveAtDesc("ds", TestUsers.ALICE))
                .thenReturn(Optional.of(explicit));
        when(sessions.findById(explicit.getId())).thenReturn(Optional.of(explicit));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(explicit.getId(), agent.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(
                        anyString(), any(), any(), anyList(), any(), any(), anyInt(), any()))
                .thenReturn(mock(Agent.class));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        Optional<LlmConfig> cfg = service.activate("term-1", "ds", TestUsers.ALICE, projectA);
        assertTrue(cfg.isPresent());
        // The explicit one wins: the active session id is the explicit session's id, not the
        // legacy one's.
        assertEquals(
                Optional.of(explicit.getId()),
                service.activeSession("term-1"),
                "explicit-workspace session wins over legacy NULL-workspace when both match");
    }

    @Test
    void deleteRemovesOnlyTheSelectedSessionWithSameName() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        String projectA = fakeDir("ws-A");
        String projectB = fakeDir("ws-B");
        // Two names match, but deletion addresses only the selected immutable ID.
        SessionEntity inA = new SessionEntity(TestUsers.ALICE, "ds", projectA);
        SessionEntity inB = new SessionEntity(TestUsers.ALICE, "ds", projectB);
        when(sessions.findByUserId(TestUsers.ALICE)).thenReturn(List.of(inA, inB));

        SessionService service =
                new SessionService(
                        sessions,
                        agents,
                        patterns,
                        agentService,
                        liveAgents,
                        loader,
                        tierRegistry,
                        new WorkspaceAdmissionPolicy(
                                new DeployerPolicyConfiguration(),
                                mock(ProtectedSetResolver.class),
                                TestUsers.registry()),
                        mock(ScopedPluginStorage.class),
                        mock(HitlRecordRepository.class),
                        mock(PluginManager.class),
                        mock(EventManager.class),
                        mock(PluginDataCleanup.class),
                        mock(RequestContinuationStore.class));

        when(sessions.findById(inA.getId())).thenReturn(Optional.of(inA));
        assertTrue(service.delete(TestUsers.ALICE, inA.getId()));
        verify(sessions).delete(inA);
        verify(sessions, never()).delete(inB);
        verify(liveAgents, never()).stopSession(UUID.fromString(inB.getId()));
        assertFalse(service.delete(TestUsers.BOB, inA.getId()));
        verify(sessions, times(1)).delete(any(SessionEntity.class));
    }
}
