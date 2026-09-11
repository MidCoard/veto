package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.util.Nullness;

class GroupMemberRemovalTest {
    private final Blackboard board = new Blackboard();
    private final GroupRegistry registry = new GroupRegistry();
    private final GroupOrchestrator orchestrator = new GroupOrchestrator(registry, board);
    private final GroupSpawner spawner = mock(ToolDocs.nonNullClass(GroupSpawner.class));

    private @NonNull Group group(DagNode.@NonNull NodeState state) {
        Group group =
                Group.create(
                                "leader",
                                "owner",
                                "brief",
                                board,
                                new ExecutionDag(UUID.randomUUID(), List.of()))
                        .withMate("mate", "review")
                        .withMate("sibling", "review");
        DagNode task =
                new DagNode(
                        "task",
                        "review",
                        "mate",
                        "review",
                        Set.of(),
                        state,
                        new DagNode.ResultNone());
        group = group.withDag(new ExecutionDag(group.groupId(), List.of(task)));
        registry.put(group);
        return group;
    }

    @Test
    void unfinishedWorkBlocksRemovalWithoutStoppingExecution() {
        for (DagNode.NodeState state :
                List.of(
                        DagNode.NodeState.PENDING,
                        DagNode.NodeState.RUNNING,
                        DagNode.NodeState.FAILED)) {
            Group group = group(state);
            var result = orchestrator.removeMate(group.groupId(), "mate", spawner);
            assertTrue(
                    result instanceof GroupOrchestrator.NodeEdit.Rejected rejected
                            && rejected.reason().contains("task"));
            assertTrue(
                    Nullness.requireNonNull(registry.get(group.groupId()))
                            .mates()
                            .containsKey("mate"));
        }
        verifyNoInteractions(spawner);
    }

    @Test
    void unconfirmedStopRetainsIdentityRejectsWorkAndCanBeConfirmedLater() {
        Group group = group(DagNode.NodeState.VERIFIED);
        when(spawner.stopMateAndConfirm(group.groupId(), "mate")).thenReturn(false, true);
        assertInstanceOf(
                ToolDocs.nonNullClass(GroupOrchestrator.NodeEdit.Rejected.class),
                orchestrator.removeMate(group.groupId(), "mate", spawner));
        assertTrue(
                Nullness.requireNonNull(registry.get(group.groupId())).mates().containsKey("mate"));
        assertInstanceOf(
                ToolDocs.nonNullClass(GroupOrchestrator.NodeEdit.Rejected.class),
                orchestrator.addNode(
                        group.groupId(), "next", "review", "review", Set.of(), "mate", false));
        assertInstanceOf(
                ToolDocs.nonNullClass(GroupOrchestrator.NodeEdit.Applied.class),
                orchestrator.addNode(
                        group.groupId(),
                        "sibling-work",
                        "review",
                        "review",
                        Set.of(),
                        "sibling",
                        false));
        assertInstanceOf(
                ToolDocs.nonNullClass(GroupOrchestrator.NodeEdit.Applied.class),
                orchestrator.removeMate(group.groupId(), "mate", spawner));
        Group updated = Nullness.requireNonNull(registry.get(group.groupId()));
        assertFalse(updated.mates().containsKey("mate"));
        assertTrue(updated.mates().containsKey("sibling"));
        assertEquals(group.dag().nodes().getFirst(), updated.dag().nodes().getFirst());
    }

    @Test
    void unknownMemberDoesNotStopOtherMembers() {
        Group group = group(DagNode.NodeState.STALE);
        assertInstanceOf(
                ToolDocs.nonNullClass(GroupOrchestrator.NodeEdit.Rejected.class),
                orchestrator.removeMate(group.groupId(), "unknown", spawner));
        verifyNoInteractions(spawner);
    }

    @Test
    void realSpawnerWaitsForMemberConfirmationAndRetainsCompletedTask() throws Exception {
        Group group = group(DagNode.NodeState.VERIFIED);
        GroupSpawner runtime =
                new GroupSpawner(board, registry, orchestrator, new MateBreakerRegistry(), 50);
        Agent agent = mock(ToolDocs.nonNullClass(Agent.class));
        when(agent.awaitTermination(any(ToolDocs.nonNullClass(Duration.class))))
                .thenReturn(false, true);
        runtime.addMate(group.groupId(), "mate", "review", (persona, binding) -> agent);
        try {
            assertInstanceOf(
                    ToolDocs.nonNullClass(GroupOrchestrator.NodeEdit.Rejected.class),
                    runtime.removeMate(group.groupId(), "mate"));
            assertTrue(
                    Nullness.requireNonNull(registry.get(group.groupId()))
                            .mates()
                            .containsKey("mate"));
            assertInstanceOf(
                    ToolDocs.nonNullClass(GroupOrchestrator.NodeEdit.Applied.class),
                    runtime.removeMate(group.groupId(), "mate"));
            verify(agent, times(1)).terminate();
            verify(agent, times(2)).awaitTermination(any(ToolDocs.nonNullClass(Duration.class)));
            assertEquals(group.dag(), Nullness.requireNonNull(registry.get(group.groupId())).dag());
        } finally {
            runtime.disband(group.groupId());
        }
    }

    @Test
    void historySaveFailureCanRetryConfirmedRemoval() throws Exception {
        Group group = group(DagNode.NodeState.VERIFIED);
        GroupSpawner runtime =
                new GroupSpawner(board, registry, orchestrator, new MateBreakerRegistry(), 50);
        Agent agent = mock(ToolDocs.nonNullClass(Agent.class));
        when(agent.awaitTermination(any(ToolDocs.nonNullClass(Duration.class)))).thenReturn(true);
        runtime.addMate(group.groupId(), "mate", "review", (persona, binding) -> agent);
        GroupHistoryStore history = mock(ToolDocs.nonNullClass(GroupHistoryStore.class));
        registry.attachHistory(history);
        doThrow(new IllegalStateException("save unavailable"))
                .doNothing()
                .when(history)
                .save(any(ToolDocs.nonNullClass(Group.class)));
        try {
            assertThrows(
                    ToolDocs.nonNullClass(IllegalStateException.class),
                    () -> runtime.removeMate(group.groupId(), "mate"));
            assertTrue(
                    Nullness.requireNonNull(registry.get(group.groupId()))
                            .mates()
                            .containsKey("mate"));
            assertInstanceOf(
                    ToolDocs.nonNullClass(GroupOrchestrator.NodeEdit.Applied.class),
                    runtime.removeMate(group.groupId(), "mate"));
            assertFalse(
                    Nullness.requireNonNull(registry.get(group.groupId()))
                            .mates()
                            .containsKey("mate"));
            verify(agent, times(1)).terminate();
        } finally {
            runtime.disband(group.groupId());
        }
    }
}
