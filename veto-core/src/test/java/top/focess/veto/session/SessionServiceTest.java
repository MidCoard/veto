package top.focess.veto.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.model.AgentEntity;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTierRegistry;

class SessionServiceTest {

    private final @NonNull ModelTierRegistry tierRegistry =
            mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(ModelTierRegistry.class));

    /**
     * A stable cwd used by the terminal-side tests. Matches sessions whose workspaceRoots is null
     * (treated as "any workspace" for legacy data) — so all the historical "happy path" tests pass
     * through {@code isInWorkspace} without changes.
     */
    private static final @NonNull String CWD =
            Objects.requireNonNull(System.getProperty("user.dir"), "user.dir");

    @BeforeEach
    void stubResolve() {
        // SessionService resolves the owner's tier to a concrete binding at create + activate; the
        // mock stands in for the per-user registry (no JPA needed for these unit tests).
        when(tierRegistry.resolve(anyString(), any()))
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
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        AgentPatternEntity pattern =
                new AgentPatternEntity(
                        "coder", "DEEPSEEK", "deepseek-v4", "pattern-coder", "alice");
        when(patterns.findByNameAndOwner("coder", "alice")).thenReturn(Optional.of(pattern));
        when(sessions.findByOwnerAndNameAndWorkspaceRoots(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(sessions.save(
                        any(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionEntity.class))))
                .thenAnswer(i -> i.getArgument(0));
        when(agents.save(any(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentEntity.class))))
                .thenAnswer(i -> i.getArgument(0));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);

        SessionEntity session = service.createSession("alice", "coder");
        assertEquals(ToolResultPresentationMode.CONTENT_ONLY, session.getToolResultPresentation());
        requirePrimaryAgentId(session, "primary agent created and linked");
        verify(agents)
                .save(any(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentEntity.class)));
    }

    @Test
    void createSessionWithCustomName() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        AgentPatternEntity pattern =
                new AgentPatternEntity(
                        "coder", "DEEPSEEK", "deepseek-v4", "pattern-coder", "alice");
        when(patterns.findByNameAndOwner("coder", "alice")).thenReturn(Optional.of(pattern));
        when(sessions.findByOwnerAndNameAndWorkspaceRoots(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(sessions.save(
                        any(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionEntity.class))))
                .thenAnswer(i -> i.getArgument(0));
        when(agents.save(any(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentEntity.class))))
                .thenAnswer(i -> i.getArgument(0));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);

        SessionEntity session =
                service.createSession(
                        "alice",
                        "coder",
                        "mysession",
                        CWD,
                        ToolResultPresentationMode.CONTENT_WITH_METADATA);
        assertEquals("mysession", session.getName());
        assertEquals(
                ToolResultPresentationMode.CONTENT_WITH_METADATA,
                session.getToolResultPresentation());

        ArgumentCaptor<SessionEntity> captor =
                ArgumentCaptor.forClass(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionEntity.class));
        verify(sessions, atLeastOnce()).save(captor.capture());
        assertEquals("mysession", captor.getAllValues().get(0).getName());
    }

    @Test
    void createSessionGeneratesUniqueNameFromPattern() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        AgentPatternEntity pattern =
                new AgentPatternEntity(
                        "coder", "DEEPSEEK", "deepseek-v4", "pattern-coder", "alice");
        when(patterns.findByNameAndOwner("coder", "alice")).thenReturn(Optional.of(pattern));
        when(sessions.findByOwnerAndNameAndWorkspaceRoots(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(sessions.save(
                        any(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionEntity.class))))
                .thenAnswer(i -> i.getArgument(0));
        when(agents.save(any(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentEntity.class))))
                .thenAnswer(i -> i.getArgument(0));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);

        SessionEntity session = service.createSession("alice", "coder", null, CWD);
        assertTrue(
                session.getName().startsWith("coder-"),
                "an implicit session name must be derived from the pattern name");
        assertTrue(
                session.getName().matches("coder-[0-9a-f]{8}"),
                "generated name must be patternName-8hex, got: " + session.getName());
    }

    @Test
    void activateResolvesConfigFromPrimaryAgent() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        SessionEntity session = new SessionEntity("alice", "coder");
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

        when(sessions.findByOwner("alice")).thenReturn(List.of(session));
        when(sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc("coder", "alice"))
                .thenReturn(Optional.of(session));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(session.getId(), agent.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(
                        anyString(), any(), any(), anyList(), any(), any(), any()))
                .thenReturn(mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(Agent.class)));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);

        Optional<LlmConfig> cfg = service.activate("term-1", "coder", "alice", CWD);
        assertTrue(cfg.isPresent());
        assertEquals(ProviderType.DEEPSEEK, cfg.get().provider());
        // The agent's tier (TOP) resolves live via the model-tier registry (mocked here).
        assertEquals("deepseek-chat", cfg.get().model());
        assertEquals(Optional.of(session.getId()), service.activeSession("term-1"));
    }

    @Test
    void deactivateClearsActive() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        SessionEntity session = new SessionEntity("alice", "coder");
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

        when(sessions.findByOwner("alice")).thenReturn(List.of(session));
        when(sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc("coder", "alice"))
                .thenReturn(Optional.of(session));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(session.getId(), agent.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(
                        anyString(), any(), any(), anyList(), any(), any(), any()))
                .thenReturn(mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(Agent.class)));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);
        service.activate("term-1", "coder", "alice", CWD);
        service.deactivate("term-1");
        assertTrue(service.activeSession("term-1").isEmpty());
    }

    @Test
    void deactivateUserDetachesUserTerminals() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        SessionEntity session = new SessionEntity("alice", "coder");
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

        when(sessions.findByOwner("alice")).thenReturn(List.of(session));
        when(sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc("coder", "alice"))
                .thenReturn(Optional.of(session));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(session.getId(), agent.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(
                        anyString(), any(), any(), anyList(), any(), any(), any()))
                .thenReturn(mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(Agent.class)));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);
        service.activate("term-1", "coder", "alice", CWD);
        assertTrue(service.activeSession("term-1").isPresent());

        service.deactivateUser("alice");
        assertTrue(
                service.activeSession("term-1").isEmpty(),
                "unified logout detaches the user's terminals");
    }

    @Test
    void resumeLastSessionActivatesOwnersMostRecent() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        SessionEntity session = new SessionEntity("alice", "coder");
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

        when(sessions.findByOwner("alice")).thenReturn(List.of(session));
        when(sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc("coder", "alice"))
                .thenReturn(Optional.of(session));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(session.getId(), agent.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(
                        anyString(), any(), any(), anyList(), any(), any(), any()))
                .thenReturn(mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(Agent.class)));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);

        Optional<LlmConfig> cfg = service.resumeLastSession("term-1", "alice", CWD);
        assertTrue(cfg.isPresent(), "last session auto-resumed");
        assertEquals(Optional.of(session.getId()), service.activeSession("term-1"));
    }

    @Test
    void resumeLastSessionEmptyWhenOwnerHasNoSessions() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        when(sessions.findByOwner("alice")).thenReturn(List.of());

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);
        assertTrue(service.resumeLastSession("term-1", "alice", CWD).isEmpty());
        assertTrue(service.activeSession("term-1").isEmpty());
    }

    @Test
    void createSessionRejectsUnknownPattern() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));
        when(patterns.findByNameAndOwner("nope", "alice")).thenReturn(Optional.empty());

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);
        assertThrows(
                top.focess.veto.agent.mcp.ToolDocs.nonNullClass(IllegalArgumentException.class),
                () -> service.createSession("alice", "nope"));
    }

    @Test
    void deleteCascadesAndDetachesTerminal() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        SessionEntity session = new SessionEntity("alice", "coder");
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

        when(sessions.findByOwner("alice")).thenReturn(List.of(session));
        when(sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc("coder", "alice"))
                .thenReturn(Optional.of(session));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(session.getId(), agent.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(
                        anyString(), any(), any(), anyList(), any(), any(), any()))
                .thenReturn(mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(Agent.class)));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);
        service.activate("term-1", "coder", "alice", CWD);
        assertTrue(service.activeSession("term-1").isPresent());

        boolean removed = service.delete("alice", "coder");
        assertTrue(removed, "delete should report the session removed");

        verify(agentService).remove(session.getId());
        verify(agents).deleteBySessionId(session.getId());
        verify(sessions).delete(session);
        assertTrue(service.activeSession("term-1").isEmpty(), "terminal detached on delete");
    }

    @Test
    void deleteReturnsFalseForUnknownSession() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));
        when(sessions.findByOwner("alice")).thenReturn(List.of());
        when(sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc("nope", "alice"))
                .thenReturn(Optional.empty());

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);
        assertFalse(service.delete("alice", "nope"));
        verify(agentService, never()).remove(anyString());
    }

    @Test
    void activateSeedsReplayedHistoryIntoAgent() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        SessionEntity session = new SessionEntity("alice", "coder");
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
        when(sessions.findByOwner("alice")).thenReturn(List.of(session));
        when(sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc("coder", "alice"))
                .thenReturn(Optional.of(session));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(session.getId(), agent.getId())).thenReturn(history);
        when(agentService.getOrCreateAgent(
                        anyString(), any(), any(), anyList(), any(), any(), any()))
                .thenReturn(mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(Agent.class)));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);
        service.activate("term-1", "coder", "alice", CWD);

        // The replayed history loaded from the durable log is threaded into getOrCreateAgent so the
        // agent seeds it on first creation (idempotent across re-activates and resume).
        verify(agentService)
                .getOrCreateAgent(
                        eq(session.getId()),
                        eq(agent.getId()),
                        any(),
                        eq(history),
                        any(),
                        eq("alice"),
                        any(),
                        eq(ToolResultPresentationMode.CONTENT_ONLY));
    }

    // ── workspace binding ─────────────────────────────────────────────────

    /** Synthetic terminal cwd rooted under the JVM tmp dir; deterministic across runs. */
    private static @NonNull String fakeDir(@NonNull String name) {
        return java.nio.file.Path.of(
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
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        @NonNull String projectA = fakeDir("veto-test-ws-A");
        @NonNull String projectB = fakeDir("veto-test-ws-B");
        @NonNull String projectASub = fakeDir("veto-test-ws-A/sub");

        SessionEntity inA = new SessionEntity("alice", "alpha", projectA);
        SessionEntity inB = new SessionEntity("alice", "beta", projectB);
        SessionEntity legacy = new SessionEntity("alice", "legacy", null); // no binding

        when(sessions.findByOwner("alice")).thenReturn(List.of(inA, inB, legacy));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);

        // Terminal in projectA: sees inA (exact) + legacy (null = any) — NOT inB.
        List<SessionEntity> seenInA = service.listSessions("alice", projectA);
        assertEquals(2, seenInA.size());
        assertTrue(seenInA.contains(inA));
        assertTrue(seenInA.contains(legacy));
        assertFalse(seenInA.contains(inB));

        // Terminal in a subdirectory of projectA: still in-scope of inA.
        List<SessionEntity> seenInASub = service.listSessions("alice", projectASub);
        assertEquals(2, seenInASub.size());
        assertTrue(seenInASub.contains(inA));
        assertFalse(seenInASub.contains(inB));

        // Terminal in projectB: sees inB + legacy — NOT inA.
        List<SessionEntity> seenInB = service.listSessions("alice", projectB);
        assertEquals(2, seenInB.size());
        assertTrue(seenInB.contains(inB));
        assertTrue(seenInB.contains(legacy));
        assertFalse(seenInB.contains(inA));
    }

    @Test
    void listSessionsUnscopedReturnsAll() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        SessionEntity a = new SessionEntity("alice", "alpha", fakeDir("ws-A"));
        SessionEntity b = new SessionEntity("alice", "beta", fakeDir("ws-B"));
        when(sessions.findByOwner("alice")).thenReturn(List.of(a, b));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);

        // Single-arg REST-style list returns every session regardless of binding.
        assertEquals(2, service.listSessions("alice").size());
    }

    @Test
    void activateRejectsOutOfWorkspaceCwd() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        @NonNull String projectA = fakeDir("veto-test-ws-A");
        @NonNull String projectB = fakeDir("veto-test-ws-B");
        SessionEntity session = new SessionEntity("alice", "alpha", projectA);
        when(sessions.findByOwner("alice")).thenReturn(List.of(session));
        when(sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc("alpha", "alice"))
                .thenReturn(Optional.of(session));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);

        IllegalArgumentException ex =
                assertThrows(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                IllegalArgumentException.class),
                        () -> service.activate("term-1", "alpha", "alice", projectB));
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
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        @NonNull String projectA = fakeDir("veto-test-ws-A");
        @NonNull String projectASub = fakeDir("veto-test-ws-A/inner");
        SessionEntity session = new SessionEntity("alice", "alpha", projectA);
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

        when(sessions.findByOwner("alice")).thenReturn(List.of(session));
        when(sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc("alpha", "alice"))
                .thenReturn(Optional.of(session));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(session.getId(), agent.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(
                        anyString(), any(), any(), anyList(), any(), any(), any()))
                .thenReturn(mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(Agent.class)));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);

        // Subdirectory of the bound workspace: still in-scope.
        Optional<LlmConfig> cfg = service.activate("term-1", "alpha", "alice", projectASub);
        assertTrue(cfg.isPresent(), "subdirectory of bound workspace activates cleanly");
    }

    @Test
    void resumeLastSessionSkipsOutOfWorkspaceSessions() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        @NonNull String projectA = fakeDir("veto-test-ws-A");
        @NonNull String projectB = fakeDir("veto-test-ws-B");
        // The user's only session is in projectA; the terminal just opened in projectB.
        // (The session is the most-recent overall — the case where a naive
        // findFirstByOwnerOrderByLastActiveAtDesc would silently resume into it.)
        SessionEntity inA = new SessionEntity("alice", "alpha", projectA);
        try {
            java.lang.reflect.Field f =
                    top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionEntity.class)
                            .getDeclaredField("lastActiveAt");
            f.setAccessible(true);
            f.set(inA, Instant.now());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        when(sessions.findByOwner("alice")).thenReturn(List.of(inA));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);

        Optional<LlmConfig> cfg = service.resumeLastSession("term-1", "alice", projectB);
        assertTrue(
                cfg.isEmpty(),
                "auto-resume must NOT silently resume into a session bound to a different"
                        + " workspace; the terminal sees 'No active session in this workspace'");
        assertTrue(service.activeSession("term-1").isEmpty());
    }

    @Test
    void resumeLastSessionPicksMostRecentInWorkspace() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        @NonNull String projectA = fakeDir("veto-test-ws-A");
        // Two sessions in projectA; the newer one is alpha, the older one is zulu.
        SessionEntity older = new SessionEntity("alice", "zulu", projectA);
        try {
            java.lang.reflect.Field f =
                    top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionEntity.class)
                            .getDeclaredField("lastActiveAt");
            f.setAccessible(true);
            f.set(older, Instant.now().minusSeconds(60));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        SessionEntity newer = new SessionEntity("alice", "alpha", projectA);
        try {
            java.lang.reflect.Field f =
                    top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionEntity.class)
                            .getDeclaredField("lastActiveAt");
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

        when(sessions.findByOwner("alice")).thenReturn(List.of(older, newer));
        when(sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc("alpha", "alice"))
                .thenReturn(Optional.of(newer));
        when(sessions.findById(newer.getId())).thenReturn(Optional.of(newer));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(newer.getId(), agent.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(
                        anyString(), any(), any(), anyList(), any(), any(), any()))
                .thenReturn(mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(Agent.class)));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);

        Optional<LlmConfig> cfg = service.resumeLastSession("term-1", "alice", projectA);
        assertTrue(cfg.isPresent());
        assertEquals(
                Optional.of(newer.getId()),
                service.activeSession("term-1"),
                "most-recent in-workspace session wins, not the older one");
    }

    // ── workspace-scoped uniqueness ──────────────────────────────────────

    @Test
    void createSessionAllowsSameNameInDifferentWorkspace() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        @NonNull String projectA = fakeDir("ws-A");
        @NonNull String projectB = fakeDir("ws-B");
        AgentPatternEntity pattern =
                new AgentPatternEntity(
                        "coder", "DEEPSEEK", "deepseek-v4", "pattern-coder", "alice");
        when(patterns.findByNameAndOwner("coder", "alice")).thenReturn(Optional.of(pattern));
        // No row with (alice, "coder", projectB) yet — the row for (alice, "coder", projectA) is
        // in a different workspace, so it doesn't match this exact (name, workspaceRoots) lookup.
        when(sessions.findByOwnerAndNameAndWorkspaceRoots(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(sessions.save(
                        any(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionEntity.class))))
                .thenAnswer(i -> i.getArgument(0));
        when(agents.save(any(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentEntity.class))))
                .thenAnswer(i -> i.getArgument(0));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);

        SessionEntity created = service.createSession("alice", "coder", null, projectB);
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
        ArgumentCaptor<SessionEntity> captor =
                ArgumentCaptor.forClass(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionEntity.class));
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
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        @NonNull String projectA = fakeDir("ws-A");
        AgentPatternEntity pattern =
                new AgentPatternEntity(
                        "coder", "DEEPSEEK", "deepseek-v4", "pattern-coder", "alice");
        SessionEntity existing = new SessionEntity("alice", "ds", projectA);
        when(patterns.findByNameAndOwner("coder", "alice")).thenReturn(Optional.of(pattern));
        when(sessions.findByOwnerAndNameAndWorkspaceRoots("alice", "ds", projectA))
                .thenReturn(Optional.of(existing));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);

        IllegalArgumentException ex =
                assertThrows(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                IllegalArgumentException.class),
                        () -> service.createSession("alice", "coder", "ds", projectA));
        assertTrue(
                String.valueOf(ex.getMessage()).contains("ds"),
                "error names the session so the user can identify it");
        assertTrue(
                String.valueOf(ex.getMessage()).contains(projectA),
                "error names the workspace so the user knows which one conflicts");
        verify(sessions, never())
                .save(any(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionEntity.class)));
    }

    @Test
    void createSessionImplicitNameSucceedsWhenPatternNameTaken() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        @NonNull String projectA = fakeDir("ws-A");
        AgentPatternEntity pattern =
                new AgentPatternEntity(
                        "coder", "DEEPSEEK", "deepseek-v4", "pattern-coder", "alice");
        SessionEntity existing = new SessionEntity("alice", "coder", projectA);
        when(patterns.findByNameAndOwner("coder", "alice")).thenReturn(Optional.of(pattern));
        // The bare pattern name 'coder' is already taken in this workspace, but any generated
        // name 'coder-xxxxxxxx' is free - so /session create coder (no explicit name) succeeds.
        when(sessions.findByOwnerAndNameAndWorkspaceRoots("alice", "coder", projectA))
                .thenReturn(Optional.of(existing));
        when(sessions.findByOwnerAndNameAndWorkspaceRoots(
                        eq("alice"),
                        argThat(name -> name != null && name.startsWith("coder-")),
                        eq(projectA)))
                .thenReturn(Optional.empty());
        when(sessions.save(
                        any(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionEntity.class))))
                .thenAnswer(i -> i.getArgument(0));
        when(agents.save(any(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentEntity.class))))
                .thenAnswer(i -> i.getArgument(0));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);

        SessionEntity created = service.createSession("alice", "coder", null, projectA);
        assertTrue(
                created.getName().matches("coder-[0-9a-f]{8}"),
                "must generate coder-xxxxxxxx when bare 'coder' is taken, got: "
                        + created.getName());
        assertEquals(projectA, created.getWorkspaceRoots());
    }

    @Test
    void activatePicksExplicitWorkspaceOverLegacyNull() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        @NonNull String projectA = fakeDir("ws-A");
        // Two "ds" sessions for alice: one legacy (NULL = matches any cwd), one explicitly bound
        // to projectA. A terminal in projectA should activate the explicit one, not the legacy.
        SessionEntity legacy = new SessionEntity("alice", "ds", null);
        SessionEntity explicit = new SessionEntity("alice", "ds", projectA);
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

        when(sessions.findByOwner("alice")).thenReturn(List.of(legacy, explicit));
        when(sessions.findFirstByNameAndOwnerOrderByLastActiveAtDesc("ds", "alice"))
                .thenReturn(Optional.of(explicit));
        when(sessions.findById(explicit.getId())).thenReturn(Optional.of(explicit));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(explicit.getId(), agent.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(
                        anyString(), any(), any(), anyList(), any(), any(), any()))
                .thenReturn(mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(Agent.class)));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);

        Optional<LlmConfig> cfg = service.activate("term-1", "ds", "alice", projectA);
        assertTrue(cfg.isPresent());
        // The explicit one wins: the active session id is the explicit session's id, not the
        // legacy one's.
        assertEquals(
                Optional.of(explicit.getId()),
                service.activeSession("term-1"),
                "explicit-workspace session wins over legacy NULL-workspace when both match");
    }

    @Test
    void deleteRemovesAllSessionsWithSameName() {
        SessionRepository sessions =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionRepository.class));
        AgentInstanceRepository agents =
                mock(
                        top.focess.veto.agent.mcp.ToolDocs.nonNullClass(
                                AgentInstanceRepository.class));
        AgentPatternRepository patterns =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentPatternRepository.class));
        AgentService agentService =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(AgentService.class));
        SessionHistoryLoader loader =
                mock(top.focess.veto.agent.mcp.ToolDocs.nonNullClass(SessionHistoryLoader.class));

        @NonNull String projectA = fakeDir("ws-A");
        @NonNull String projectB = fakeDir("ws-B");
        // Two "ds" sessions for alice in different workspaces: the REST caller has no workspace
        // context to disambiguate with, so both are removed.
        SessionEntity inA = new SessionEntity("alice", "ds", projectA);
        SessionEntity inB = new SessionEntity("alice", "ds", projectB);
        when(sessions.findByOwner("alice")).thenReturn(List.of(inA, inB));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader, tierRegistry);

        assertTrue(service.delete("alice", "ds"));
        verify(sessions).delete(inA);
        verify(sessions).delete(inB);
    }
}
