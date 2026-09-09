package top.focess.veto.monitor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.group.Blackboard;
import top.focess.veto.group.DagNode;
import top.focess.veto.group.ExecutionDag;
import top.focess.veto.group.Group;
import top.focess.veto.group.GroupRegistry;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.sandbox.BackgroundTaskManager;

class MonitorServiceTest {
    private final @NonNull MonitorRepository repository = mock();
    private final @NonNull ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final @NonNull GroupRegistry groups = new GroupRegistry();
    private final @NonNull MonitorService service =
            new MonitorService(repository, mapper, groups, new SessionAgentRegistry());
    private final @NonNull String session = UUID.randomUUID().toString();

    @Test
    void processExitBeforeStartNotificationDoesNotResurrectOrRedeliver() {
        UUID instance = UUID.randomUUID();
        Instant started = Instant.now().minusSeconds(2);
        var exited =
                new BackgroundTaskManager.TaskInfo(
                        "bg-1",
                        "agent",
                        "echo done",
                        ".",
                        started,
                        false,
                        0,
                        1L,
                        Instant.now(),
                        UUID.fromString(session),
                        instance);
        service.observeProcess("owner", exited, BackgroundTaskManager.ExitCause.USER_STOP);
        service.observeProcess(
                "owner",
                new BackgroundTaskManager.TaskInfo(
                        "bg-1",
                        "agent",
                        "echo done",
                        ".",
                        started,
                        true,
                        null,
                        1L,
                        null,
                        UUID.fromString(session),
                        instance),
                BackgroundTaskManager.ExitCause.NATURAL);
        var event = service.pending("agent", session).getFirst();
        assertTrue(event.content().contains("USER_STOP"));
        service.acknowledge("other", event);
        assertEquals(1, service.pending("agent", session).size());
        service.acknowledge("agent", event);
        service.observeProcess("owner", exited, BackgroundTaskManager.ExitCause.USER_STOP);
        assertTrue(service.pending("agent", session).isEmpty());
        var record = service.list("owner", session).getFirst();
        assertEquals("COMPLETED", record.state());
        assertEquals(1, record.deliveredEvents().size());
    }

    @Test
    void processInstanceIdentityPreventsTaskCounterCollisions() {
        for (int i = 0; i < 2; i++) {
            service.observeProcess(
                    "owner",
                    new BackgroundTaskManager.TaskInfo(
                            "bg-1",
                            "agent",
                            "echo done",
                            ".",
                            Instant.now(),
                            false,
                            0,
                            1L,
                            Instant.now(),
                            UUID.fromString(session),
                            UUID.randomUUID()),
                    BackgroundTaskManager.ExitCause.NATURAL);
        }
        assertEquals(2, service.pending("agent", session).size());
        assertTrue(service.pending("agent", UUID.randomUUID().toString()).isEmpty());
    }

    @Test
    void timeTriggerIsDurableAndOnlyDeliveredOnce() {
        var due = Instant.now().plusSeconds(60);
        var r = service.createTimer("owner", session, "agent", "Review", due);
        service.tickAt(due.plusSeconds(1));
        service.tickAt(due.plusSeconds(2));
        var events = service.pending("agent", session);
        assertEquals(1, events.size());
        assertEquals("COMPLETED", service.list("owner", session).getFirst().state());
        service.acknowledge("agent", events.getFirst());
        service.tickAt(due.plusSeconds(3));
        assertTrue(service.pending("agent", session).isEmpty());
        assertEquals(r.id(), events.getFirst().monitorId());
    }

    @Test
    void pauseHoldsOverdueWakeAndResumeDoesNotRepeatAcknowledgedOccurrence() {
        var due = Instant.now().plusSeconds(60);
        var r = service.createTimer("owner", session, "agent", "Review", due);
        service.control("owner", session, "agent", r.id(), "pause");
        service.tickAt(due.plusSeconds(1));
        assertTrue(service.pending("agent", session).isEmpty());
        service.control("owner", session, "agent", r.id(), "resume");
        service.tickAt(due.plusSeconds(2));
        service.acknowledge("agent", service.pending("agent", session).getFirst());
        service.control("owner", session, "agent", r.id(), "pause");
        service.control("owner", session, "agent", r.id(), "resume");
        service.tickAt(due.plusSeconds(3));
        assertTrue(service.pending("agent", session).isEmpty());
    }

    @Test
    void cancellationRemovesQueuedWakeButCannotControlAnotherOwner() {
        var due = Instant.now().plusSeconds(60);
        var r = service.createTimer("owner", session, "agent", "Review", due);
        service.tickAt(due.plusSeconds(1));
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> service.control("other", session, "agent", r.id(), "cancel"));
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> service.control("owner", session, "other", r.id(), "cancel"));
        service.control("owner", session, "agent", r.id(), "cancel");
        assertTrue(service.pending("agent", session).isEmpty());
        assertTrue(service.list("other", session).isEmpty());
    }

    @Test
    void restartRetainsPendingOccurrence() throws Exception {
        List<MonitorEntity> saved = new ArrayList<>();
        when(repository.save(any()))
                .thenAnswer(
                        call -> {
                            MonitorEntity row = call.getArgument(0);
                            if (row == null) throw new AssertionError("Missing saved row");
                            saved.add(row);
                            return row;
                        });
        var due = Instant.now().plusSeconds(60);
        service.createTimer("owner", session, "agent", "Review", due);
        service.tickAt(due.plusSeconds(1));
        when(repository.findAll()).thenReturn(List.of(saved.getLast()));
        var restored = new MonitorService(repository, mapper, groups, new SessionAgentRegistry());
        restored.restore();
        restored.tickAt(due.plusSeconds(2));
        assertEquals(service.pending("agent", session), restored.pending("agent", session));
    }

    @Test
    void groupOutcomeIsCapturedWithoutInspectAndQueryDoesNotConsumeIt() {
        UUID id = UUID.randomUUID();
        var node =
                new DagNode(
                        "work",
                        "Calculate",
                        "mate",
                        "arbitrary responsibility",
                        Set.of(),
                        DagNode.NodeState.VERIFIED,
                        new DagNode.ResultSuccess("42"));
        Group group =
                new Group(
                        id,
                        "leader",
                        "user",
                        "Calculate",
                        new ExecutionDag(id, List.of(node)),
                        new Blackboard(),
                        Map.of("mate", "arbitrary responsibility"),
                        Group.GroupState.COMPLETED,
                        Instant.now(),
                        null,
                        "owner",
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        UUID.fromString(session));
        groups.put(group);
        assertTrue(service.hasUndeliveredGroup("leader"));
        service.list("owner", session);
        service.tick();
        assertEquals(1, service.pending("leader", session).size());
        assertTrue(service.pending("leader", session).getFirst().content().contains("42"));
        service.acknowledge("leader", service.pending("leader", session).getFirst());
        assertFalse(service.hasUndeliveredGroup("leader"));
        assertFalse(service.hasGroupWork("leader"));
    }

    @Test
    void terminatingReceiverCancelsItsWakeOnly() {
        var due = Instant.now().plusSeconds(60);
        service.createTimer("owner", session, "a", "Review A", due);
        service.createTimer("owner", session, "b", "Review B", due);
        service.cancelForAgent("a");
        service.tickAt(due.plusSeconds(1));
        assertTrue(service.pending("a", session).isEmpty());
        assertEquals(1, service.pending("b", session).size());
    }
}
