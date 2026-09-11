package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.util.Nullness;

class GroupTaskCancellationTest {
    private final Blackboard board = new Blackboard();
    private final GroupRegistry registry = new GroupRegistry();
    private final GroupOrchestrator orchestrator = new GroupOrchestrator(registry, board);
    private final GroupSpawner spawner = mock(ToolDocs.nonNullClass(GroupSpawner.class));

    private @NonNull Group setup() {
        Group group =
                Group.create(
                                "leader",
                                "owner",
                                "brief",
                                board,
                                new ExecutionDag(UUID.randomUUID(), List.of()))
                        .withMate("mate", "review")
                        .withMate("other", "review");
        registry.put(group);
        orchestrator.addNode(
                group.groupId(),
                "first",
                "first",
                "review",
                Set.of(),
                "mate",
                false,
                "request-one");
        orchestrator.addNode(
                group.groupId(),
                "dependent",
                "dependent",
                "review",
                Set.of("first"),
                "other",
                false);
        orchestrator.addNode(
                group.groupId(), "sibling", "sibling", "review", Set.of(), "other", false);
        return Nullness.requireNonNull(registry.get(group.groupId()));
    }

    private @NonNull DagNode node(@NonNull UUID id, @NonNull String name) {
        return Nullness.requireNonNull(registry.get(id)).dag().nodes().stream()
                .filter(n -> n.nodeId().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void queuedCancellationDoesNotRunAndDoesNotSatisfyDependencies() {
        Group group = setup();
        assertInstanceOf(
                ToolDocs.nonNullClass(GroupOrchestrator.NodeEdit.Applied.class),
                orchestrator.cancelTask(group.groupId(), "first", spawner));
        orchestrator.tick(group.groupId());
        assertEquals(DagNode.NodeState.CANCELLED, node(group.groupId(), "first").state());
        assertEquals(DagNode.NodeState.PENDING, node(group.groupId(), "dependent").state());
        assertEquals(DagNode.NodeState.RUNNING, node(group.groupId(), "sibling").state());
        assertTrue(board.readFor(group.groupId(), "mate").isEmpty());
        verifyNoInteractions(spawner);
    }

    @Test
    void unconfirmedCancellationKeepsOtherwiseFinishedGroupActive() {
        Group group = setup();
        registry.put(
                group.withDag(
                        new ExecutionDag(
                                group.groupId(), List.of(node(group.groupId(), "first")))));
        orchestrator.tick(group.groupId());
        orchestrator.cancelTask(group.groupId(), "first", spawner);
        assertInstanceOf(
                ToolDocs.nonNullClass(GroupOrchestrator.NodeEdit.Rejected.class),
                orchestrator.removeNode(group.groupId(), "first"));
        orchestrator.tick(group.groupId());
        assertEquals(DagNode.NodeState.CANCEL_REQUESTED, node(group.groupId(), "first").state());
        assertTrue(Nullness.requireNonNull(registry.get(group.groupId())).isActive());
    }

    @Test
    void pendingCancellationIgnoresLateReportsAndTickConfirmsBeforeMateReuse() {
        Group group = setup();
        orchestrator.tick(group.groupId());
        DagNode first = node(group.groupId(), "first");
        String dispatch = Nullness.requireNonNull(first.dispatchId());
        when(spawner.cancelDispatch(
                        eq(group.groupId()),
                        eq("mate"),
                        eq(dispatch),
                        any(ToolDocs.nonNullClass(Duration.class))))
                .thenReturn(false, true);
        assertInstanceOf(
                ToolDocs.nonNullClass(GroupOrchestrator.NodeEdit.Rejected.class),
                orchestrator.cancelTask(group.groupId(), "first", spawner));
        assertEquals(DagNode.NodeState.CANCEL_REQUESTED, node(group.groupId(), "first").state());
        board.post(
                new BlackboardMessage(
                        "late",
                        group.groupId(),
                        "mate",
                        "LEADER",
                        BlackboardMessage.MessageType.ACCEPT,
                        "first:accept-base64:bGF0ZQ==",
                        0,
                        dispatch));
        orchestrator.addNode(
                group.groupId(), "next", "new independent task", "review", Set.of(), "mate", false);
        orchestrator.tick(group.groupId());
        assertEquals(DagNode.NodeState.CANCELLED, node(group.groupId(), "first").state());
        assertEquals("request-one", node(group.groupId(), "first").requestId());
        assertEquals(dispatch, node(group.groupId(), "first").dispatchId());
        assertEquals(DagNode.NodeState.RUNNING, node(group.groupId(), "next").state());
        assertEquals(DagNode.NodeState.PENDING, node(group.groupId(), "dependent").state());
        assertEquals(DagNode.NodeState.RUNNING, node(group.groupId(), "sibling").state());
        assertInstanceOf(
                ToolDocs.nonNullClass(GroupOrchestrator.NodeEdit.Applied.class),
                orchestrator.cancelTask(group.groupId(), "first", spawner));
    }
}
