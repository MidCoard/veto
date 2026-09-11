package top.focess.veto.monitor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.group.GroupRegistry;
import top.focess.veto.session.SessionService;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.UserContext;

class MonitorAgentActivatorTest {
    @Test
    void waitsForTheOwnerAndRestoresCallerContextOnFailureAndSuccess() {
        @NonNull SessionService sessions = mock();
        @NonNull KeysteadVault vault = mock();
        var registry = new SessionAgentRegistry();
        var activator = new MonitorAgentActivator(sessions, registry, vault, new GroupRegistry());
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
            activator.wake(record);
            verifyNoInteractions(sessions);
            assertEquals("bob", UserContext.get());
            when(vault.isUnlocked("alice")).thenReturn(true);
            when(sessions.activateForMonitor(session, "alice", "agent"))
                    .thenAnswer(
                            invocation -> {
                                assertEquals("alice", UserContext.get());
                                throw new IllegalStateException("temporary recovery failure");
                            });
            assertThrows(IllegalStateException.class, () -> activator.wake(record));
            assertEquals("bob", UserContext.get());
            @NonNull VetoAgent agent = mock();
            when(agent.id()).thenReturn("agent");
            doAnswer(
                            invocation -> {
                                assertEquals("alice", UserContext.get());
                                registry.register(session, agent);
                                return true;
                            })
                    .when(sessions)
                    .activateForMonitor(session, "alice", "agent");
            activator.wake(record);
            activator.wake(record);
            verify(agent, times(2)).signalMonitor();
            verify(sessions, times(2)).activateForMonitor(session, "alice", "agent");
            assertEquals("bob", UserContext.get());
        } finally {
            UserContext.clear();
        }
    }
}
