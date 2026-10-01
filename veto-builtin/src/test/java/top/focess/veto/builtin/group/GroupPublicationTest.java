package top.focess.veto.builtin.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

class GroupPublicationTest {

    @Test
    void failedRunningSaveCannotPublishWork() {
        var registry = new FailingRegistry();
        var board = new Blackboard();
        var group = pendingGroup(board);
        registry.put(group);
        registry.fail = DagNode.NodeState.RUNNING;
        var orchestrator = new GroupOrchestrator(registry, board);

        assertThrows(IllegalStateException.class, () -> orchestrator.tick(group.groupId()));
        assertTrue(board.readAll(group.groupId()).isEmpty());
        assertEquals(
                DagNode.NodeState.PENDING,
                current(registry, group).dag().nodes().getFirst().state());

        orchestrator.tick(group.groupId());
        var running = current(registry, group).dag().nodes().getFirst();
        assertEquals(DagNode.NodeState.RUNNING, running.state());
        assertEquals(1, board.size(group.groupId()));
        assertEquals(running.dispatchId(), board.readAll(group.groupId()).getFirst().dispatchId());
    }

    @Test
    void failedPublicationAcknowledgementRetriesTheSameMessageAndAttempt() {
        var registry = new GroupRegistry();
        var board =
                new Blackboard() {
                    private boolean fail = true;

                    @Override
                    public @NonNull BlackboardMessage post(@NonNull BlackboardMessage message) {
                        var running = current(registry, message.groupId());
                        assertEquals(
                                DagNode.NodeState.RUNNING,
                                running.dag().nodes().getFirst().state());
                        assertEquals(
                                running.dag().nodes().getFirst().dispatchId(),
                                message.dispatchId());
                        var stamped = super.post(message);
                        if (fail) {
                            fail = false;
                            throw new IllegalStateException("publication acknowledgement failed");
                        }
                        return stamped;
                    }
                };
        var group = pendingGroup(board);
        registry.put(group);
        var orchestrator = new GroupOrchestrator(registry, board);

        assertThrows(IllegalStateException.class, () -> orchestrator.tick(group.groupId()));
        var published = board.readAll(group.groupId()).getFirst();
        orchestrator.tick(group.groupId());
        assertEquals(List.of(published), board.readAll(group.groupId()));
        assertEquals(
                published.dispatchId(),
                current(registry, group).dag().nodes().getFirst().dispatchId());
    }

    @Test
    void postCommitInvalidationFailureRetainsPendingPublication() {
        var registry = new FailingRegistry();
        var board = new Blackboard();
        var group = pendingGroup(board);
        registry.put(group);
        registry.fail = DagNode.NodeState.RUNNING;
        registry.afterCommit = true;
        var orchestrator = new GroupOrchestrator(registry, board);

        assertThrows(IllegalStateException.class, () -> orchestrator.tick(group.groupId()));
        var dispatchId = current(registry, group).dag().nodes().getFirst().dispatchId();
        assertTrue(board.readAll(group.groupId()).isEmpty());
        orchestrator.tick(group.groupId());
        assertEquals(1, board.size(group.groupId()));
        assertEquals(dispatchId, board.readAll(group.groupId()).getFirst().dispatchId());
    }

    @Test
    void failedReportSaveLeavesCompletionAvailableForTickAndInspectionRetry() {
        for (boolean inspect : List.of(false, true)) {
            var registry = new FailingRegistry();
            var board = new Blackboard();
            var group = pendingGroup(board);
            registry.put(group);
            var orchestrator = new GroupOrchestrator(registry, board);
            orchestrator.tick(group.groupId());
            GroupTestMessages.accept(board, group.groupId(), "Mate-A", "n1");
            registry.fail = DagNode.NodeState.VERIFIED;

            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        if (inspect) orchestrator.inspect(group.groupId(), 0);
                        else orchestrator.tick(group.groupId());
                    });
            assertEquals(
                    DagNode.NodeState.RUNNING,
                    current(registry, group).dag().nodes().getFirst().state());
            if (inspect) orchestrator.inspect(group.groupId(), 0);
            else orchestrator.tick(group.groupId());
            assertEquals(
                    DagNode.NodeState.VERIFIED,
                    current(registry, group).dag().nodes().getFirst().state());
            assertEquals(2, board.size(group.groupId()));
        }
    }

    private static final class FailingRegistry extends GroupRegistry {
        private DagNode.NodeState fail;
        private boolean afterCommit;

        @Override
        public void put(@NonNull Group group) {
            if (group.dag().nodes().stream().anyMatch(node -> node.state() == fail)) {
                fail = null;
                if (afterCommit) super.put(group);
                throw new IllegalStateException("state save or invalidation failed");
            }
            super.put(group);
        }
    }

    private static @NonNull Group pendingGroup(@NonNull Blackboard board) {
        var group =
                Group.create(
                        "Leader",
                        "user",
                        "work",
                        board,
                        new ExecutionDag(UUID.randomUUID(), List.of()));
        return group.withMate("Mate-A", "coding")
                .withDag(
                        new ExecutionDag(
                                group.groupId(),
                                List.of(
                                        new DagNode(
                                                "n1",
                                                "work",
                                                "Mate-A",
                                                "coding",
                                                Set.of(),
                                                DagNode.NodeState.PENDING,
                                                new DagNode.ResultNone()))));
    }

    private static @NonNull Group current(@NonNull GroupRegistry registry, @NonNull Group group) {
        return current(registry, group.groupId());
    }

    private static @NonNull Group current(@NonNull GroupRegistry registry, @NonNull UUID id) {
        var current = registry.get(id);
        if (current == null) throw new AssertionError("group missing");
        return current;
    }
}
