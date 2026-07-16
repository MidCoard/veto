package top.focess.veto.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.AgentService;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.model.AgentEntity;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.AgentPatternEntity;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;

class SessionServiceTest {

    @Test
    void createSessionBuildsPrimaryAgent() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);

        AgentPatternEntity pattern =
                new AgentPatternEntity(
                        "coder", "DEEPSEEK", "deepseek-v4", "pattern-coder", "alice");
        when(patterns.findByNameAndOwner("coder", "alice")).thenReturn(Optional.of(pattern));
        when(sessions.findByNameAndOwner(anyString(), anyString())).thenReturn(Optional.empty());
        when(sessions.save(any(SessionEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(agents.save(any(AgentEntity.class))).thenAnswer(i -> i.getArgument(0));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader);

        SessionEntity session = service.createSession("alice", "coder");
        assertNotNull(session.getPrimaryAgentId(), "primary agent created and linked");
        verify(agents).save(any(AgentEntity.class));
    }

    @Test
    void activateResolvesConfigFromPrimaryAgent() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);

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

        when(sessions.findByNameAndOwner("coder", "alice")).thenReturn(Optional.of(session));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(session.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(anyString(), any(), anyList(), any()))
                .thenReturn(mock(Agent.class));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader);

        Optional<LlmConfig> cfg = service.activate("term-1", "coder", "alice");
        assertTrue(cfg.isPresent());
        assertEquals(ProviderType.DEEPSEEK, cfg.get().provider());
        assertEquals("deepseek-v4", cfg.get().model());
        assertEquals(Optional.of(session.getId()), service.activeSession("term-1"));
    }

    @Test
    void deactivateClearsActive() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);

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

        when(sessions.findByNameAndOwner("coder", "alice")).thenReturn(Optional.of(session));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(session.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(anyString(), any(), anyList(), any()))
                .thenReturn(mock(Agent.class));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader);
        service.activate("term-1", "coder", "alice");
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

        when(sessions.findByNameAndOwner("coder", "alice")).thenReturn(Optional.of(session));
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        when(agents.findById(agent.getId())).thenReturn(Optional.of(agent));
        when(loader.load(session.getId())).thenReturn(List.of());
        when(agentService.getOrCreateAgent(anyString(), any(), anyList(), any()))
                .thenReturn(mock(Agent.class));

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader);
        service.activate("term-1", "coder", "alice");
        assertTrue(service.activeSession("term-1").isPresent());

        service.deactivateUser("alice");
        assertTrue(
                service.activeSession("term-1").isEmpty(),
                "unified logout detaches the user's terminals");
    }

    @Test
    void createSessionRejectsUnknownPattern() {
        SessionRepository sessions = mock(SessionRepository.class);
        AgentInstanceRepository agents = mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = mock(AgentPatternRepository.class);
        AgentService agentService = mock(AgentService.class);
        SessionHistoryLoader loader = mock(SessionHistoryLoader.class);
        when(patterns.findByNameAndOwner("nope", "alice")).thenReturn(Optional.empty());

        SessionService service =
                new SessionService(sessions, agents, patterns, agentService, loader);
        assertThrows(IllegalArgumentException.class, () -> service.createSession("alice", "nope"));
    }
}
