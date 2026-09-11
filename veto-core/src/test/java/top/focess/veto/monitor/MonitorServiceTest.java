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
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.group.Blackboard;
import top.focess.veto.group.DagNode;
import top.focess.veto.group.ExecutionDag;
import top.focess.veto.group.Group;
import top.focess.veto.group.GroupHistoryView;
import top.focess.veto.group.GroupRegistry;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.monitor.MonitorRecord.ActivationState;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.util.Nullness;

class MonitorServiceTest {
    @Test
    void cancelledActivationRetainsTheEventAndRetriesPersistence() throws Exception {
        var due = Instant.now().plusSeconds(10);
        service.createTimer("owner", session, "agent", "Cancelled request", due, "request");
        service.tickAt(due.plusSeconds(1));
        var event = service.pending("agent", session).getFirst();
        service.activationCancelled("other-agent", session, event);
        service.activationCancelled("agent", UUID.randomUUID().toString(), event);
        assertEquals(List.of(event), service.pending("agent", session));
        doThrow(new IllegalStateException("database unavailable")).when(repository).save(any());
        assertThrows(
                IllegalStateException.class,
                () -> service.activationCancelled("agent", session, event));
        assertEquals(List.of(event), service.pending("agent", session));
        doAnswer(
                        invocation -> {
                            MonitorEntity row = invocation.getArgument(0);
                            return row;
                        })
                .when(repository)
                .save(any());
        service.activationCancelled("agent", session, event);
        assertTrue(service.pending("agent", session).isEmpty());
        var saved = service.list("owner", session).getFirst();
        assertEquals(List.of(event), saved.deliveredEvents());
        var activation = saved.activationStates().get(event.id());
        if (activation == null) throw new AssertionError("Missing cancelled activation");
        assertEquals(ActivationState.CANCELLED, activation.state());
        when(repository.findAll())
                .thenReturn(
                        List.of(new MonitorEntity(saved.id(), mapper.writeValueAsString(saved))));
        service.restore();
        assertTrue(service.pending("agent", session).isEmpty());
        assertEquals(List.of(event), service.list("owner", session).getFirst().deliveredEvents());
    }

    @Test
    void timerRequestSurvivesControlRestoreAndDelivery() throws Exception {
        var due = Instant.now().plusSeconds(60);
        var timer =
                service.createTimer("owner", session, "agent", "Cancel task", due, "request-timer");
        service.control("owner", session, "agent", timer.id(), "pause");
        service.control("owner", session, "agent", timer.id(), "resume");
        var saved = service.list("owner", session).getFirst();
        assertEquals("request-timer", saved.requestId());
        String json = mapper.writeValueAsString(saved);
        when(repository.findAll()).thenReturn(List.of(new MonitorEntity(saved.id(), json)));
        service.restore();
        service.tickAt(due.plusSeconds(1));
        var event = service.pending("agent", session).getFirst();
        assertEquals("request-timer", event.requestId());
        assertNull(event.dispatchId());
        service.acknowledge("agent", event);
        var delivered = service.list("owner", session).getFirst();
        assertEquals("request-timer", delivered.requestId());
        assertEquals("request-timer", delivered.deliveredEvents().getFirst().requestId());
        String oldJson = json.replace(",\"requestId\":\"request-timer\"", "");
        assertNull(
                mapper.readValue(oldJson, ToolDocs.nonNullClass(MonitorRecord.class)).requestId());
    }

    @Test
    void failedOfflineActivationKeepsTheSameNotificationForTheNextTick() {
        @NonNull MonitorAgentActivator activator = mock();
        service.attachActivator(activator);
        var due = Instant.now().plusSeconds(10);
        service.createTimer("owner", session, "agent", "Review", due);
        doThrow(new IllegalStateException("recovery unavailable"))
                .doNothing()
                .when(activator)
                .wake(any());
        service.tickAt(due.plusSeconds(1));
        var pending = service.pending("agent", session);
        assertEquals(1, pending.size());
        service.tickAt(due.plusSeconds(2));
        assertEquals(pending, service.pending("agent", session));
        verify(activator, times(2)).wake(any());
    }

    private final @NonNull MonitorRepository repository = mock();
    private final @NonNull ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final @NonNull GroupRegistry groups = new GroupRegistry();
    private final @NonNull MonitorService service =
            new MonitorService(repository, mapper, groups, new SessionAgentRegistry());
    private final @NonNull String session = UUID.randomUUID().toString();

    @Test
    void oldDeliveredReceiptDoesNotInventExecutionSuccessOrReplay() throws Exception {
        var event =
                new MonitorRecord.Event("old-event", "old", "TIME_ONCE", "Review", Instant.now());
        var old =
                new MonitorRecord(
                        "old",
                        "owner",
                        session,
                        "agent",
                        "TIME_ONCE",
                        "Review",
                        null,
                        Instant.now(),
                        "COMPLETED",
                        Map.of("fired", "true"),
                        List.of(),
                        Instant.now(),
                        List.of(event));
        String json = mapper.writeValueAsString(old).replace(",\"activations\":{}", "");
        assertFalse(json.contains("activations"));
        when(repository.findAll()).thenReturn(List.of(new MonitorEntity(old.id(), json)));
        service.restore();
        var restored = service.list("owner", session).getFirst();
        assertEquals(List.of(event), restored.deliveredEvents());
        assertTrue(restored.activationStates().isEmpty());
        assertTrue(service.pending("agent", session).isEmpty());
    }

    @Test
    void appendedReceiptRecoversButInProgressWorkIsInterruptedWithoutReplay() throws Exception {
        var due = Instant.now().plusSeconds(60);
        service.createTimer("owner", session, "agent", "Review", due);
        service.tickAt(due.plusSeconds(1));
        var event = service.pending("agent", session).getFirst();
        service.acknowledge("agent", event);
        var appended = service.list("owner", session).getFirst();
        assertEquals(
                ActivationState.APPENDED,
                Nullness.requireNonNull(appended.activationStates().get(event.id())).state());
        assertEquals(List.of(event), service.pending("agent", session));
        when(repository.findAll())
                .thenReturn(
                        List.of(
                                new MonitorEntity(
                                        appended.id(), mapper.writeValueAsString(appended))));
        var restored =
                new MonitorService(
                        repository, mapper, new GroupRegistry(), new SessionAgentRegistry());
        restored.restore();
        assertEquals(List.of(event), restored.pending("agent", session));
        restored.activationStarted("agent", event);
        var running = restored.list("owner", session).getFirst();
        assertEquals(
                ActivationState.RUNNING,
                Nullness.requireNonNull(running.activationStates().get(event.id())).state());
        assertTrue(restored.pending("agent", session).isEmpty());
        when(repository.findAll())
                .thenReturn(
                        List.of(
                                new MonitorEntity(
                                        running.id(), mapper.writeValueAsString(running))));
        var restartedAgain =
                new MonitorService(
                        repository, mapper, new GroupRegistry(), new SessionAgentRegistry());
        restartedAgain.restore();
        assertEquals(
                ActivationState.INTERRUPTED,
                Nullness.requireNonNull(
                                restartedAgain
                                        .list("owner", session)
                                        .getFirst()
                                        .activationStates()
                                        .get(event.id()))
                        .state());
        assertTrue(restartedAgain.pending("agent", session).isEmpty());
    }

    @Test
    void failedClaimStaysEligibleAndFailedCompletionWriteRetriesWithoutReexecution() {
        var due = Instant.now().plusSeconds(60);
        service.createTimer("owner", session, "agent", "Review", due);
        service.tickAt(due.plusSeconds(1));
        var event = service.pending("agent", session).getFirst();
        service.acknowledge("agent", event);
        AtomicBoolean fail = new AtomicBoolean(true);
        when(repository.save(any()))
                .thenAnswer(
                        invocation -> {
                            if (fail.get()) throw new IllegalStateException("Storage unavailable");
                            return invocation.getArgument(0);
                        });
        assertThrows(IllegalStateException.class, () -> service.activationStarted("agent", event));
        assertEquals(List.of(event), service.pending("agent", session));
        fail.set(false);
        service.activationStarted("agent", event);
        fail.set(true);
        service.activationCompleted("agent", event, true);
        assertEquals(
                ActivationState.RUNNING,
                Nullness.requireNonNull(
                                service.list("owner", session)
                                        .getFirst()
                                        .activationStates()
                                        .get(event.id()))
                        .state());
        assertTrue(service.pending("agent", session).isEmpty());
        fail.set(false);
        service.tickAt(due.plusSeconds(2));
        assertEquals(
                ActivationState.COMPLETED,
                Nullness.requireNonNull(
                                service.list("owner", session)
                                        .getFirst()
                                        .activationStates()
                                        .get(event.id()))
                        .state());
        assertTrue(service.pending("agent", session).isEmpty());
    }

    @Test
    void pausedOrCancelledAppendedReceiptCannotBeClaimed() {
        var due = Instant.now().plusSeconds(60);
        var timer = service.createTimer("owner", session, "agent", "Review", due);
        service.tickAt(due.plusSeconds(1));
        var event = service.pending("agent", session).getFirst();
        service.acknowledge("agent", event);
        service.control("owner", session, "agent", timer.id(), "pause");
        assertTrue(service.pending("agent", session).isEmpty());
        assertThrows(IllegalStateException.class, () -> service.activationStarted("agent", event));
        service.control("owner", session, "agent", timer.id(), "resume");
        assertEquals(List.of(event), service.pending("agent", session));
        service.control("owner", session, "agent", timer.id(), "cancel");
        assertTrue(service.pending("agent", session).isEmpty());
        assertThrows(IllegalStateException.class, () -> service.activationStarted("agent", event));
    }

    private static void deliver(
            @NonNull MonitorService monitor,
            @NonNull String agent,
            MonitorRecord.@NonNull Event event) {
        monitor.acknowledge(agent, event);
        monitor.activationStarted(agent, event);
        monitor.activationCompleted(agent, event, true);
    }

    @Test
    void restoredGroupDeliversExistingObservationOnlyAfterRecoveryIsReady() throws Exception {
        var group =
                Group.create(
                        "leader",
                        "user",
                        "Review",
                        new Blackboard(),
                        new ExecutionDag(UUID.randomUUID(), List.of()),
                        "owner",
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        UUID.fromString(session));
        group =
                group.withDag(
                        new ExecutionDag(
                                group.groupId(),
                                List.of(
                                        new DagNode(
                                                "task",
                                                "Review",
                                                "mate",
                                                "review",
                                                Set.of(),
                                                DagNode.NodeState.VERIFIED,
                                                new DagNode.ResultSuccess("saved report"),
                                                0,
                                                "dispatch",
                                                "request"))));
        groups.put(group);
        service.tickAt(Instant.now());
        var saved = service.list("owner", session).getFirst();
        var event = saved.pending().getFirst();
        when(repository.findAll())
                .thenReturn(
                        List.of(new MonitorEntity(saved.id(), mapper.writeValueAsString(saved))));
        var restoredGroups = new GroupRegistry();
        var restored =
                new MonitorService(repository, mapper, restoredGroups, new SessionAgentRegistry());
        restored.restore();
        assertEquals("INTERRUPTED", restored.list("owner", session).getFirst().state());
        assertTrue(restored.pending("leader", session).isEmpty());
        restoredGroups.put(group.withState(Group.GroupState.RECOVERING, Instant.now()));
        restored.tickAt(Instant.now());
        assertTrue(restored.pending("leader", session).isEmpty());
        restoredGroups.put(group.withState(Group.GroupState.ACTIVE, Instant.now()));
        restored.tickAt(Instant.now());
        assertEquals(List.of(event), restored.pending("leader", session));
        assertEquals(saved.seen(), restored.list("owner", session).getFirst().seen());
        restored.tickAt(Instant.now());
        assertEquals(List.of(event), restored.pending("leader", session));
        deliver(restored, "leader", event);
        restored.tickAt(Instant.now());
        assertTrue(restored.pending("leader", session).isEmpty());
        assertEquals(List.of(event), restored.list("owner", session).getFirst().deliveredEvents());
    }

    @Test
    void groupCompletionAndPersistedOutcomeBelongToTheirOriginatingRequest() throws Exception {
        UUID id = UUID.randomUUID();
        var oldNode =
                new DagNode(
                        "old",
                        "Old task",
                        "mate",
                        "review",
                        Set.of(),
                        DagNode.NodeState.VERIFIED,
                        new DagNode.ResultSuccess("old result"),
                        0,
                        "dispatch-old",
                        "request-old");
        var newNode =
                new DagNode(
                        "new",
                        "New task",
                        "mate",
                        "review",
                        Set.of(),
                        DagNode.NodeState.RUNNING,
                        new DagNode.ResultNone(),
                        0,
                        "dispatch-new",
                        "request-new");
        Group group =
                new Group(
                        id,
                        "leader",
                        "user",
                        "Review",
                        new ExecutionDag(id, List.of(oldNode, newNode)),
                        new Blackboard(),
                        Map.of("mate", "review"),
                        Group.GroupState.ACTIVE,
                        Instant.now(),
                        null,
                        "owner",
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        UUID.fromString(session));
        groups.put(group);
        assertTrue(service.hasGroupWork("leader", "request-new"));
        assertFalse(service.hasGroupWork("leader", "request-old"));
        assertFalse(service.hasGroupWork("leader", "unrelated-request"));
        assertFalse(service.hasUndeliveredGroup("leader", "request-new"));
        assertTrue(service.hasUndeliveredGroup("leader", "request-old"));
        var event = service.pending("leader", session).getFirst();
        assertEquals("request-old", event.requestId());
        assertEquals("dispatch-old", event.dispatchId());
        var persisted =
                mapper.readValue(
                        mapper.writeValueAsString(event),
                        ToolDocs.nonNullClass(MonitorRecord.Event.class));
        assertEquals(event, persisted);
        var history = GroupHistoryView.nodes(group);
        assertEquals("request-old", history.getFirst().requestId());
        assertEquals("dispatch-new", history.getLast().dispatchId());
        deliver(service, "leader", event);
        assertFalse(service.hasUndeliveredGroup("leader", "request-old"));
        assertTrue(service.hasGroupWork("leader", "request-new"));
        for (var state :
                List.of(
                        DagNode.NodeState.FAILED,
                        DagNode.NodeState.CANCELLED,
                        DagNode.NodeState.INTERRUPTED)) {
            var stopped =
                    new DagNode(
                            "new",
                            "Stopped",
                            "mate",
                            "review",
                            Set.of(),
                            state,
                            new DagNode.ResultNone(),
                            0,
                            "dispatch-new",
                            "request-new");
            var dependent =
                    new DagNode(
                            "child",
                            "Blocked",
                            "mate",
                            "review",
                            Set.of("new"),
                            DagNode.NodeState.PENDING,
                            new DagNode.ResultNone(),
                            0,
                            null,
                            "request-new");
            groups.put(group.withDag(new ExecutionDag(id, List.of(stopped, dependent))));
            assertFalse(service.hasGroupWork("leader", "request-new"), state.toString());
        }
        var queued =
                new DagNode(
                        "queued",
                        "New independent work",
                        "mate",
                        "review",
                        Set.of(),
                        DagNode.NodeState.PENDING,
                        new DagNode.ResultNone(),
                        0,
                        null,
                        "request-new");
        groups.put(group.withDag(new ExecutionDag(id, List.of(queued))));
        assertTrue(service.hasGroupWork("leader", "request-new"));
        assertFalse(service.hasGroupWork("leader", "request-old"));
    }

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
        deliver(service, "agent", event);
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
        deliver(service, "agent", events.getFirst());
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
        deliver(service, "agent", service.pending("agent", session).getFirst());
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void groupOutcomeIsCapturedWithoutInspectAndQueryDoesNotConsumeIt(boolean interrupted) {
        UUID id = UUID.randomUUID();
        var node =
                new DagNode(
                        "work",
                        "Calculate",
                        "mate",
                        "arbitrary responsibility",
                        Set.of(),
                        interrupted ? DagNode.NodeState.INTERRUPTED : DagNode.NodeState.VERIFIED,
                        interrupted ? new DagNode.ResultNone() : new DagNode.ResultSuccess("42"));
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
        assertTrue(
                service.pending("leader", session)
                        .getFirst()
                        .content()
                        .contains(interrupted ? "outcome is unknown" : "42"));
        deliver(service, "leader", service.pending("leader", session).getFirst());
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
