package top.focess.veto.bus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.group.GroupRegistry;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.monitor.MonitorRepository;
import top.focess.veto.monitor.MonitorService;
import top.focess.veto.sandbox.BackgroundTaskManager;

class TaskEventBridgeTest {
    @Test
    void failedExitObservationRetriesWithoutLosingIdentityOrRegressingToStarted() {
        @NonNull BackgroundTaskManager tasks = mock();
        @NonNull DeltaBroker broker = mock();
        @NonNull SessionRepository sessions = mock();
        @NonNull MonitorRepository repository = mock();
        var session = new SessionEntity("owner", "process");
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        var monitor =
                new MonitorService(
                        repository,
                        new ObjectMapper().findAndRegisterModules(),
                        new GroupRegistry(),
                        new SessionAgentRegistry());
        var bridge = new TaskEventBridge(tasks, broker, sessions, monitor);
        UUID instance = UUID.randomUUID();
        Instant start = Instant.now();
        var running =
                new BackgroundTaskManager.TaskInfo(
                        "bg-1",
                        "agent",
                        "work",
                        ".",
                        start,
                        true,
                        null,
                        1L,
                        null,
                        UUID.fromString(session.getId()),
                        instance,
                        "origin");
        var exited =
                new BackgroundTaskManager.TaskInfo(
                        "bg-1",
                        "agent",
                        "work",
                        ".",
                        start,
                        false,
                        0,
                        1L,
                        start.plusSeconds(1),
                        UUID.fromString(session.getId()),
                        instance,
                        "origin");
        AtomicBoolean unavailable = new AtomicBoolean(true);
        when(repository.save(any()))
                .thenAnswer(
                        invocation -> {
                            if (unavailable.get())
                                throw new IllegalStateException("Database unavailable");
                            return invocation.getArgument(0);
                        });
        bridge.onTaskStarted(running);
        bridge.onTaskExited(exited, BackgroundTaskManager.ExitCause.USER_STOP);
        bridge.onTaskStarted(running);
        assertTrue(monitor.pending("agent", session.getId()).isEmpty());
        unavailable.set(false);
        bridge.retryObservations();
        var event = monitor.pending("agent", session.getId()).getFirst();
        assertEquals("origin", event.requestId());
        assertEquals(instance.toString(), event.dispatchId());
        assertTrue(event.content().contains("USER_STOP"));
        bridge.retryObservations();
        bridge.onTaskExited(exited, BackgroundTaskManager.ExitCause.USER_STOP);
        assertEquals(1, monitor.pending("agent", session.getId()).size());
        verifyNoInteractions(tasks);
    }

    @Test
    void deletedSessionDoesNotAcquireAnObservationDuringRetry() {
        @NonNull BackgroundTaskManager tasks = mock();
        @NonNull DeltaBroker broker = mock();
        @NonNull SessionRepository sessions = mock();
        @NonNull MonitorService monitor = mock();
        UUID session = UUID.randomUUID();
        when(sessions.findById(session.toString()))
                .thenThrow(new IllegalStateException("Database unavailable"))
                .thenReturn(Optional.empty());
        var bridge = new TaskEventBridge(tasks, broker, sessions, monitor);
        var exited =
                new BackgroundTaskManager.TaskInfo(
                        "bg-1",
                        "agent",
                        "work",
                        ".",
                        Instant.now(),
                        false,
                        0,
                        1L,
                        Instant.now(),
                        session,
                        UUID.randomUUID(),
                        "origin");
        bridge.onTaskExited(exited);
        bridge.retryObservations();
        bridge.retryObservations();
        verify(sessions, times(2)).findById(session.toString());
        verifyNoInteractions(monitor, tasks);
    }
}
