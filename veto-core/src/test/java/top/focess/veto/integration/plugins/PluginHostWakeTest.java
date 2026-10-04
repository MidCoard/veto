package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.builtin.monitor.MonitorRecord;
import top.focess.veto.bus.SessionInvalidations;
import top.focess.veto.memory.TurnRecordRepository;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.UserContext;

class PluginHostWakeTest {
    @Test
    void waitsForTheOwnerAndRestoresCallerContextOnFailureAndSuccess() {
        SessionService sessions = mock(SessionService.class);
        KeysteadVault vault = mock(KeysteadVault.class);
        var registry =
                new SessionAgentRegistry(
                        Mockito.mock(AgentInstanceRepository.class),
                        Mockito.mock(TurnRecordRepository.class),
                        Mockito.mock(SessionInvalidations.class));
        var activator = MonitorTestSupport.host(sessions, registry, vault);
        UUID session = UUID.randomUUID();
        var record =
                new MonitorRecord(
                        "timer",
                        "alice",
                        session.toString(),
                        "agent",
                        "TIME_ONCE",
                        "Review",
                        null,
                        Instant.now(),
                        "COMPLETED",
                        Map.of(),
                        List.of(),
                        Instant.now());
        when(vault.isUnlocked()).thenReturn(true);
        UserContext.set("bob");
        try {
            activator.wake(record.owner(), record.sessionId(), record.agentId());
            verifyNoInteractions(sessions);
            assertEquals("bob", UserContext.get());
            when(vault.isUnlocked("alice")).thenReturn(true);
            when(sessions.activateForObservation(session, "alice", "agent"))
                    .thenAnswer(
                            invocation -> {
                                assertEquals("alice", UserContext.get());
                                throw new IllegalStateException("temporary recovery failure");
                            });
            assertThrows(
                    IllegalStateException.class,
                    () -> activator.wake(record.owner(), record.sessionId(), record.agentId()));
            assertEquals("bob", UserContext.get());
            VetoAgent agent = mock(VetoAgent.class);
            when(agent.id()).thenReturn("agent");
            when(agent.name()).thenReturn("Agent");
            when(agent.persona())
                    .thenReturn(new AgentPersona("agent", "Agent", "Wake probe", Set.of()));
            when(agent.state()).thenReturn(AgentState.IDLE);
            doAnswer(
                            invocation -> {
                                assertEquals("alice", UserContext.get());
                                if (registry.agents(session).isEmpty())
                                    registry.register(session, agent);
                                return true;
                            })
                    .when(sessions)
                    .activateForObservation(session, "alice", "agent");
            activator.wake(record.owner(), record.sessionId(), record.agentId());
            activator.wake(record.owner(), record.sessionId(), record.agentId());
            verify(agent, times(2)).signalWork();
            verify(sessions, times(3)).activateForObservation(session, "alice", "agent");
            assertEquals("bob", UserContext.get());
        } finally {
            UserContext.clear();
        }
    }
}
