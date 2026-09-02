package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for the Part 2 group orchestration engine (leader_mate_topology.md §3). */
@SuppressWarnings("initialization.field.uninitialized")
class GroupOrchestratorTest {

    private @NonNull Blackboard blackboard;
    private @NonNull GroupRegistry registry;
    private @NonNull GroupOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        blackboard = new Blackboard();
        registry = new GroupRegistry();
        orchestrator = new GroupOrchestrator(registry, blackboard);
    }

    @Test
    void tickDispatchesRootNode() {
        UUID groupId = UUID.randomUUID();
        Group g =
                Group.create(
                        "Leader-1",
                        "user-1",
                        "build",
                        blackboard,
                        ExecutionDag.linear(groupId, List.of("n1", "n2")));
        g = g.withMate("Mate-A", "coding");
        // Assign n1 to Mate-A (Leader authors this assignment).
        ExecutionDag dag =
                g.dag()
                        .withNode(
                                "n1",
                                new DagNode(
                                        "n1",
                                        "first",
                                        "Mate-A",
                                        "coding",
                                        Set.of(),
                                        DagNode.NodeState.PENDING,
                                        new DagNode.ResultNone()));
        g = g.withDag(dag);
        registry.put(g);

        Group ticked = requireGroup(orchestrator.tick(g.groupId()));
        // After tick: n1 should be RUNNING (dispatched), blackboard has a TASK_DISPATCH for Mate-A.
        DagNode n1 = findNode(ticked, "n1");
        assertEquals(DagNode.NodeState.RUNNING, n1.state());
        List<BlackboardMessage> dispatched = blackboard.readFor(g.groupId(), "Mate-A");
        assertEquals(1, dispatched.size());
        assertEquals(BlackboardMessage.MessageType.TASK_DISPATCH, dispatched.get(0).type());
    }

    @Test
    void acceptAdvancesNode() {
        Group g = setupGroup();
        registry.put(g);
        orchestrator.tick(g.groupId());
        // Simulate Mate-A accepting n1.
        orchestrator.simulateAccept(g.groupId(), "Mate-A", "n1");
        Group ticked = requireGroup(orchestrator.tick(g.groupId()));
        assertEquals(DagNode.NodeState.VERIFIED, findNode(ticked, "n1").state());
    }

    @Test
    void feedbackMarksNodeFailed() {
        Group g = setupGroup();
        registry.put(g);
        orchestrator.tick(g.groupId());
        orchestrator.simulateFeedback(g.groupId(), "Mate-A", "n1", "test failure");
        Group ticked = requireGroup(orchestrator.tick(g.groupId()));
        assertEquals(DagNode.NodeState.FAILED, findNode(ticked, "n1").state());
    }

    @Test
    void dispatchDagLinearChainProgresses() {
        Group g = setupGroup();
        registry.put(g);

        // Step 1: tick — n1 dispatched.
        Group t1 = requireGroup(orchestrator.tick(g.groupId()));
        assertEquals(DagNode.NodeState.RUNNING, findNode(t1, "n1").state());

        // Step 2: Mate-A accepts n1.
        orchestrator.simulateAccept(g.groupId(), "Mate-A", "n1");
        Group t2 = requireGroup(orchestrator.tick(g.groupId()));
        assertEquals(DagNode.NodeState.VERIFIED, findNode(t2, "n1").state());
        // Now n2 has its deps (n1) VERIFIED, so n2 is dispatched in the same tick.
        assertEquals(DagNode.NodeState.RUNNING, findNode(t2, "n2").state());

        // Step 3: Mate-B accepts n2. The orchestrator needs an additional tick to complete the
        // group because the dispatch of n2 happens in the same tick as the ingest of n2's
        // ACCEPT — but the group-completion check sees the just-dispatched n2 as RUNNING.
        // Run two more ticks to drain.
        orchestrator.simulateAccept(g.groupId(), "Mate-B", "n2");
        Group t3a = requireGroup(orchestrator.tick(g.groupId()));
        assertEquals(DagNode.NodeState.VERIFIED, findNode(t3a, "n2").state());
        Group t3 = requireGroup(orchestrator.tick(g.groupId()));

        // Step 4: group is complete but remains inspectable until the Leader disbands it.
        assertEquals(Group.GroupState.COMPLETED, t3.state());
    }

    @Test
    void replanFailedReturnsNodeToPending() {
        Group g = setupGroup();
        registry.put(g);
        orchestrator.tick(g.groupId());
        orchestrator.simulateFeedback(g.groupId(), "Mate-A", "n1", "needs another pass");
        Group ticked = requireGroup(orchestrator.tick(g.groupId()));
        assertEquals(DagNode.NodeState.FAILED, findNode(ticked, "n1").state());

        // Leader re-plans: the failed node goes back to PENDING.
        Group replanned = requireGroup(orchestrator.replanFailed(g.groupId(), "n1"));
        assertEquals(DagNode.NodeState.PENDING, findNode(replanned, "n1").state());
    }

    @Test
    void terminalStatusFromMateReassignsNode() {
        Group g = setupGroup();
        registry.put(g);
        // Simulate the orchestrator dispatching n1.
        orchestrator.tick(g.groupId());
        // Simulate Mate-A's breaker tripping. The terminal message's payload format is
        // "terminal:<nodeId>:<reason>". After the terminal status, the orchestrator
        // re-dispatches the node (PENDING → RUNNING in the same tick) — the assertion
        // here is that the terminal status was *processed* (not silently dropped). The
        // dispatch message on the Blackboard is the durable evidence.
        BlackboardMessage terminal =
                new BlackboardMessage(
                        UUID.randomUUID().toString(),
                        g.groupId(),
                        "Mate-A",
                        "LEADER",
                        BlackboardMessage.MessageType.STATUS,
                        "terminal:n1:breaker-trip",
                        0);
        blackboard.post(terminal);
        Group ticked = requireGroup(orchestrator.tick(g.groupId()));
        // The terminal status caused n1 to be marked PENDING (via ingest), then the dispatch
        // phase re-dispatched it (PENDING → RUNNING). The Blackboard shows the cycle: one
        // terminal STATUS, two TASK_DISPATCH messages (the original + the re-dispatch).
        List<BlackboardMessage> log = blackboard.readAll(g.groupId());
        long taskDispatches =
                log.stream()
                        .filter(m -> m.type() == BlackboardMessage.MessageType.TASK_DISPATCH)
                        .count();
        assertEquals(2, taskDispatches, "n1 should be re-dispatched after terminal status");
    }

    @Test
    void unassignedNodeIsNotDispatched() {
        UUID groupId = UUID.randomUUID();
        Group g =
                Group.create(
                        "Leader-1",
                        "user-1",
                        "build",
                        blackboard,
                        ExecutionDag.linear(groupId, List.of("n1", "n2")));
        // Don't add a mate or assign n1.
        registry.put(g);
        Group ticked = requireGroup(orchestrator.tick(g.groupId()));
        // n1 stays PENDING (Leader must assign a Mate first).
        assertEquals(DagNode.NodeState.PENDING, findNode(ticked, "n1").state());
    }

    @Test
    void staleNodeIsNotReTransitioned() {
        Group g = setupGroup();
        registry.put(g);
        // Manually mark n1 as STALE.
        ExecutionDag dag =
                g.dag()
                        .withNode(
                                "n1",
                                new DagNode(
                                        "n1",
                                        "first",
                                        "Mate-A",
                                        "coding",
                                        Set.of(),
                                        DagNode.NodeState.STALE,
                                        new DagNode.ResultNone()));
        g = g.withDag(dag);
        registry.put(g);
        // A late ACCEPT for the stale node must not flip it.
        orchestrator.simulateAccept(g.groupId(), "Mate-A", "n1");
        Group ticked = requireGroup(orchestrator.tick(g.groupId()));
        assertEquals(DagNode.NodeState.STALE, findNode(ticked, "n1").state());
    }

    @Test
    void acceptFromUnassignedSenderCannotVerifyNode() {
        Group g = setupGroup();
        registry.put(g);
        orchestrator.tick(g.groupId());

        orchestrator.simulateAccept(g.groupId(), "Mate-B", "n1");
        Group ticked = requireGroup(orchestrator.tick(g.groupId()));

        assertEquals(DagNode.NodeState.RUNNING, findNode(ticked, "n1").state());
    }

    private @NonNull Group setupGroup() {
        UUID groupId = UUID.randomUUID();
        Group g =
                Group.create(
                        "Leader-1",
                        "user-1",
                        "build",
                        blackboard,
                        ExecutionDag.linear(groupId, List.of("n1", "n2")));
        g = g.withMate("Mate-A", "coding").withMate("Mate-B", "coding");
        ExecutionDag dag =
                g.dag()
                        .withNode(
                                "n1",
                                new DagNode(
                                        "n1",
                                        "first",
                                        "Mate-A",
                                        "coding",
                                        Set.of(),
                                        DagNode.NodeState.PENDING,
                                        new DagNode.ResultNone()));
        dag =
                dag.withNode(
                        "n2",
                        new DagNode(
                                "n2",
                                "second",
                                "Mate-B",
                                "coding",
                                Set.of("n1"),
                                DagNode.NodeState.PENDING,
                                new DagNode.ResultNone()));
        g = g.withDag(dag);
        return g;
    }

    private static @NonNull DagNode findNode(@NonNull Group g, @NonNull String nodeId) {
        for (DagNode n : g.dag().nodes()) {
            if (n.nodeId().equals(nodeId)) {
                return n;
            }
        }
        throw new IllegalStateException("node not found: " + nodeId);
    }

    private static @NonNull Group requireGroup(Group group) {
        if (group == null) throw new AssertionError("expected group");
        return group;
    }
}
