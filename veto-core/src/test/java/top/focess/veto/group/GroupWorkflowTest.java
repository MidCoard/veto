package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for the Part 2 group workflow core (Group, ExecutionDag, Blackboard, GroupRegistry). */
class GroupWorkflowTest {

    private Blackboard blackboard;
    private GroupRegistry registry;

    @BeforeEach
    void setUp() {
        blackboard = new Blackboard();
        registry = new GroupRegistry();
    }

    @Test
    void blackboardAppendsInOrder() {
        UUID groupId = UUID.randomUUID();
        blackboard.post(
                new BlackboardMessage(
                        "m1",
                        groupId,
                        "LEADER",
                        "Mate-A",
                        BlackboardMessage.MessageType.TASK_DISPATCH,
                        "implement feature X",
                        0));
        blackboard.post(
                new BlackboardMessage(
                        "m2",
                        groupId,
                        "Mate-A",
                        "LEADER",
                        BlackboardMessage.MessageType.ARTIFACT_REF,
                        "/path/to/X.java",
                        0));
        List<BlackboardMessage> all = blackboard.readAll(groupId);
        assertEquals(2, all.size());
        assertEquals(1, all.get(0).turnSeq());
        assertEquals(2, all.get(1).turnSeq());
    }

    @Test
    void blackboardEnforcesHubAndSpoke() {
        UUID groupId = UUID.randomUUID();
        // Mate-to-Mate is forbidden.
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        blackboard.post(
                                new BlackboardMessage(
                                        "m1",
                                        groupId,
                                        "Mate-A",
                                        "Mate-B",
                                        BlackboardMessage.MessageType.FEEDBACK,
                                        "ping",
                                        0)));
    }

    @Test
    void blackboardReadForFiltersByReceiver() {
        UUID groupId = UUID.randomUUID();
        blackboard.post(
                new BlackboardMessage(
                        "m1",
                        groupId,
                        "LEADER",
                        "Mate-A",
                        BlackboardMessage.MessageType.TASK_DISPATCH,
                        "task A",
                        0));
        blackboard.post(
                new BlackboardMessage(
                        "m2",
                        groupId,
                        "LEADER",
                        "Mate-B",
                        BlackboardMessage.MessageType.TASK_DISPATCH,
                        "task B",
                        0));
        blackboard.post(
                new BlackboardMessage(
                        "m3",
                        groupId,
                        "Mate-A",
                        "LEADER",
                        BlackboardMessage.MessageType.ARTIFACT_REF,
                        "/path/A",
                        0));
        blackboard.post(
                new BlackboardMessage(
                        "m4",
                        groupId,
                        "Mate-B",
                        "LEADER",
                        BlackboardMessage.MessageType.ARTIFACT_REF,
                        "/path/B",
                        0));
        assertEquals(1, blackboard.readFor(groupId, "Mate-A").size());
        assertEquals(1, blackboard.readFor(groupId, "Mate-B").size());
        assertEquals(2, blackboard.readFor(groupId, "LEADER").size());
    }

    @Test
    void executionDagDispatchableOnlyAfterDepsVerified() {
        UUID groupId = UUID.randomUUID();
        ExecutionDag dag =
                new ExecutionDag(
                        groupId,
                        List.of(
                                DagNode.pending("n1", "first", "coding", Set.of()),
                                DagNode.pending("n2", "second", "coding", Set.of("n1")),
                                DagNode.pending("n3", "third", "testing", Set.of("n2"))));
        // Initially: only n1 has no deps.
        List<DagNode> ready = dag.dispatchable();
        assertEquals(1, ready.size());
        assertEquals("n1", ready.get(0).nodeId());

        // Mark n1 verified; n2 becomes dispatchable.
        ExecutionDag advanced =
                dag.withNode(
                        "n1",
                        new DagNode(
                                "n1",
                                "first",
                                "Mate-A",
                                "coding",
                                Set.of(),
                                DagNode.NodeState.VERIFIED,
                                new DagNode.ResultArtifact("/p/n1")));
        List<DagNode> ready2 = advanced.dispatchable();
        assertEquals(1, ready2.size());
        assertEquals("n2", ready2.get(0).nodeId());
    }

    @Test
    void executionDagLinearHelperProducesChain() {
        UUID groupId = UUID.randomUUID();
        ExecutionDag dag = ExecutionDag.linear(groupId, List.of("a", "b", "c"));
        assertEquals(3, dag.nodes().size());
        assertEquals(Set.of(), dag.nodes().get(0).dependsOn());
        assertEquals(Set.of("a"), dag.nodes().get(1).dependsOn());
        assertEquals(Set.of("b"), dag.nodes().get(2).dependsOn());
    }

    @Test
    void groupLifecycleCreateAndDisband() {
        UUID groupId = UUID.randomUUID();
        ExecutionDag dag = ExecutionDag.linear(groupId, List.of("n1"));
        Group g = Group.create("Leader-1", "user-1", "build feature X", blackboard, dag);
        registry.put(g);
        assertEquals(Group.GroupState.ACTIVE, registry.get(g.groupId()).state());

        registry.disband(g.groupId(), java.time.Instant.now());
        assertEquals(Group.GroupState.DISBANDED, registry.get(g.groupId()).state());
    }

    @Test
    void groupAddAndRemoveMates() {
        Group g =
                Group.create(
                        "Leader-1",
                        "user-1",
                        "build",
                        blackboard,
                        ExecutionDag.linear(UUID.randomUUID(), List.of("n1")));
        g = g.withMate("Mate-A", "coding").withMate("Mate-T", "testing");
        assertEquals(2, g.mates().size());
        assertEquals("coding", g.mates().get("Mate-A"));
        g = g.withoutMate("Mate-T");
        assertEquals(1, g.mates().size());
    }
}
