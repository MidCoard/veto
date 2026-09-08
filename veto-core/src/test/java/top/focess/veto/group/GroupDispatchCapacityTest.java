package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import top.focess.veto.util.Nullness;

class GroupDispatchCapacityTest {
    @Test
    void independentNodesOfSameSkillGetDifferentMates() {
        Blackboard board = new Blackboard();
        GroupRegistry registry = new GroupRegistry();
        AtomicInteger created = new AtomicInteger();
        GroupOrchestrator orchestrator =
                new GroupOrchestrator(
                        registry,
                        board,
                        new HeuristicLeader(),
                        (groupId, skill) -> "mate-" + created.incrementAndGet());
        Group group =
                Group.create(
                        "leader",
                        "user",
                        "parallel work",
                        board,
                        new ExecutionDag(
                                UUID.randomUUID(),
                                List.of(
                                        DagNode.pending("one", "first", "coding", Set.of()),
                                        DagNode.pending("two", "second", "coding", Set.of()))));
        registry.put(group);
        Group running = Nullness.requireNonNull(orchestrator.tick(group.groupId()));
        assertEquals(2, created.get());
        assertEquals(
                2,
                running.dag().nodes().stream()
                        .filter(n -> n.state() == DagNode.NodeState.RUNNING)
                        .count());
        assertNotEquals(
                running.dag().nodes().get(0).assignedMateId(),
                running.dag().nodes().get(1).assignedMateId());
        orchestrator.tick(group.groupId());
        assertEquals(2, board.readAll(group.groupId()).size(), "no redispatch while running");
    }

    @Test
    void explicitlyAssignedMateReceivesNextNodeOnlyAfterFirstResult() {
        Blackboard board = new Blackboard();
        GroupRegistry registry = new GroupRegistry();
        GroupOrchestrator orchestrator = new GroupOrchestrator(registry, board);
        Group group =
                Group.create(
                                "leader",
                                "user",
                                "sequential work",
                                board,
                                new ExecutionDag(
                                        UUID.randomUUID(),
                                        List.of(
                                                DagNode.pending("one", "first", "coding", Set.of()),
                                                DagNode.pending(
                                                        "two", "second", "coding", Set.of()))))
                        .withMate("mate", "coding");
        registry.put(group);
        Group running = Nullness.requireNonNull(orchestrator.tick(group.groupId()));
        assertEquals(
                1,
                running.dag().nodes().stream()
                        .filter(n -> n.state() == DagNode.NodeState.RUNNING)
                        .count());
        assertEquals(1, board.readAll(group.groupId()).size());
        board.post(
                new BlackboardMessage(
                        "answer",
                        group.groupId(),
                        "mate",
                        "LEADER",
                        BlackboardMessage.MessageType.ACCEPT,
                        "one:accept-base64:ZG9uZQ==",
                        0));
        Group advanced = Nullness.requireNonNull(orchestrator.tick(group.groupId()));
        assertEquals(DagNode.NodeState.VERIFIED, advanced.dag().nodes().get(0).state());
        assertEquals(DagNode.NodeState.RUNNING, advanced.dag().nodes().get(1).state());
        assertEquals(3, board.readAll(group.groupId()).size());
    }
}
