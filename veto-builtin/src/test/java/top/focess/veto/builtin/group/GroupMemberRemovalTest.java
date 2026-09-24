package top.focess.veto.builtin.group;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.agent.AgentHost;

class GroupMemberRemovalTest {
    private final @NonNull Blackboard board = new Blackboard();
    private final @NonNull GroupRegistry registry = new GroupRegistry();
    private final @NonNull GroupOrchestrator orchestrator = new GroupOrchestrator(registry, board);
    private final @NonNull GroupSpawner spawner = mock(ToolDocs.nonNullClass(GroupSpawner.class));

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
                    result instanceof NodeEdit.Rejected rejected
                            && rejected.reason().contains("task"));
            assertTrue(
                    GroupTestHost.required(registry.get(group.groupId()))
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
                ToolDocs.nonNullClass(NodeEdit.Rejected.class),
                orchestrator.removeMate(group.groupId(), "mate", spawner));
        assertTrue(
                GroupTestHost.required(registry.get(group.groupId())).mates().containsKey("mate"));
        assertInstanceOf(
                ToolDocs.nonNullClass(NodeEdit.Rejected.class),
                orchestrator.addNode(
                        group.groupId(), "next", "review", "review", Set.of(), "mate", false));
        assertInstanceOf(
                ToolDocs.nonNullClass(NodeEdit.Applied.class),
                orchestrator.addNode(
                        group.groupId(),
                        "sibling-work",
                        "review",
                        "review",
                        Set.of(),
                        "sibling",
                        false));
        assertInstanceOf(
                ToolDocs.nonNullClass(NodeEdit.Applied.class),
                orchestrator.removeMate(group.groupId(), "mate", spawner));
        Group updated = GroupTestHost.required(registry.get(group.groupId()));
        assertFalse(updated.mates().containsKey("mate"));
        assertTrue(updated.mates().containsKey("sibling"));
        assertEquals(group.dag().nodes().getFirst(), updated.dag().nodes().getFirst());
    }

    @Test
    void unknownMemberDoesNotStopOtherMembers() {
        Group group = group(DagNode.NodeState.STALE);
        assertInstanceOf(
                ToolDocs.nonNullClass(NodeEdit.Rejected.class),
                orchestrator.removeMate(group.groupId(), "unknown", spawner));
        verifyNoInteractions(spawner);
    }

    @Test
    void realSpawnerWaitsForMemberConfirmationAndRetainsCompletedTask() throws Exception {
        Group group = group(DagNode.NodeState.VERIFIED);
        AgentHost.Child agent = mock(ToolDocs.nonNullClass(AgentHost.Child.class));
        when(agent.awaitTermination(any(ToolDocs.nonNullClass(Duration.class))))
                .thenReturn(false, true);
        GroupSpawner runtime =
                new GroupSpawner(registry, board, (g, id, name, responsibility) -> agent);
        runtime.restoreMates(group.withoutMate("sibling"));
        try {
            assertInstanceOf(
                    ToolDocs.nonNullClass(NodeEdit.Rejected.class),
                    orchestrator.removeMate(group.groupId(), "mate", runtime));
            assertTrue(
                    GroupTestHost.required(registry.get(group.groupId()))
                            .mates()
                            .containsKey("mate"));
            assertInstanceOf(
                    ToolDocs.nonNullClass(NodeEdit.Applied.class),
                    orchestrator.removeMate(group.groupId(), "mate", runtime));
            verify(agent, times(1)).close();
            verify(agent, times(2)).awaitTermination(any(ToolDocs.nonNullClass(Duration.class)));
            assertEquals(group.dag(), GroupTestHost.required(registry.get(group.groupId())).dag());
        } finally {
            runtime.disband(group.groupId());
        }
    }

    @Test
    void historySaveFailureCanRetryConfirmedRemoval() throws Exception {
        Group group = group(DagNode.NodeState.VERIFIED);
        AgentHost.Child agent = mock(ToolDocs.nonNullClass(AgentHost.Child.class));
        when(agent.awaitTermination(any(ToolDocs.nonNullClass(Duration.class)))).thenReturn(true);
        GroupSpawner runtime =
                new GroupSpawner(registry, board, (g, id, name, responsibility) -> agent);
        runtime.restoreMates(group.withoutMate("sibling"));
        GroupHistoryStore history = mock(ToolDocs.nonNullClass(GroupHistoryStore.class));
        registry.attachHistory(history);
        doThrow(new IllegalStateException("save unavailable"))
                .doNothing()
                .when(history)
                .save(any(ToolDocs.nonNullClass(Group.class)));
        try {
            assertThrows(
                    ToolDocs.nonNullClass(IllegalStateException.class),
                    () -> orchestrator.removeMate(group.groupId(), "mate", runtime));
            assertTrue(
                    GroupTestHost.required(registry.get(group.groupId()))
                            .mates()
                            .containsKey("mate"));
            assertInstanceOf(
                    ToolDocs.nonNullClass(NodeEdit.Applied.class),
                    orchestrator.removeMate(group.groupId(), "mate", runtime));
            assertFalse(
                    GroupTestHost.required(registry.get(group.groupId()))
                            .mates()
                            .containsKey("mate"));
            verify(agent, times(1)).close();
        } finally {
            runtime.disband(group.groupId());
        }
    }
}
