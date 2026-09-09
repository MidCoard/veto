package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for the leader-and-mate group orchestration lifecycle. */
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
        GroupTestMessages.accept(blackboard, g.groupId(), "Mate-A", "n1");
        Group ticked = requireGroup(orchestrator.tick(g.groupId()));
        assertEquals(DagNode.NodeState.VERIFIED, findNode(ticked, "n1").state());
    }

    @Test
    void inspectionConsumesAcceptBeforeReturningItsMatchingNodeState() {
        Group group = setupGroup();
        registry.put(group);
        orchestrator.tick(group.groupId());
        GroupTestMessages.accept(blackboard, group.groupId(), "Mate-A", "n1");
        var inspection = orchestrator.inspect(group.groupId(), 0);
        if (inspection == null) throw new AssertionError("Missing inspection");
        assertTrue(
                inspection.messages().stream()
                        .anyMatch(m -> m.type() == BlackboardMessage.MessageType.ACCEPT));
        assertEquals(
                DagNode.NodeState.VERIFIED,
                inspection.group().nodes().stream()
                        .filter(n -> n.nodeId().equals("n1"))
                        .findFirst()
                        .orElseThrow()
                        .state());
    }

    @Test
    void feedbackMarksNodeFailed() {
        Group g = setupGroup();
        registry.put(g);
        orchestrator.tick(g.groupId());
        GroupTestMessages.feedback(blackboard, g.groupId(), "Mate-A", "n1", "test failure");
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
        GroupTestMessages.accept(blackboard, g.groupId(), "Mate-A", "n1");
        Group t2 = requireGroup(orchestrator.tick(g.groupId()));
        assertEquals(DagNode.NodeState.VERIFIED, findNode(t2, "n1").state());
        // Now n2 has its deps (n1) VERIFIED, so n2 is dispatched in the same tick.
        assertEquals(DagNode.NodeState.RUNNING, findNode(t2, "n2").state());

        // Step 3: Mate-B accepts n2. The orchestrator needs an additional tick to complete the
        // group because the dispatch of n2 happens in the same tick as the ingest of n2's
        // ACCEPT — but the group-completion check sees the just-dispatched n2 as RUNNING.
        // Run two more ticks to drain.
        GroupTestMessages.accept(blackboard, g.groupId(), "Mate-B", "n2");
        Group t3a = requireGroup(orchestrator.tick(g.groupId()));
        assertEquals(DagNode.NodeState.VERIFIED, findNode(t3a, "n2").state());
        Group t3 = requireGroup(orchestrator.tick(g.groupId()));

        // Step 4: group is complete but remains inspectable until the Leader disbands it.
        assertEquals(Group.GroupState.COMPLETED, t3.state());
    }

    @Test
    void completedGroupRetainsMatesAndAcceptsFollowUpWork() {
        Group group = setupGroup();
        registry.put(group);
        orchestrator.tick(group.groupId());
        GroupTestMessages.accept(blackboard, group.groupId(), "Mate-A", "n1");
        orchestrator.tick(group.groupId());
        GroupTestMessages.accept(blackboard, group.groupId(), "Mate-B", "n2");
        Group completed = requireGroup(orchestrator.tick(group.groupId()));
        assertEquals(Group.GroupState.COMPLETED, completed.state());
        assertEquals(group.mates(), completed.mates());
        assertNull(completed.disbandedAt());

        assertTrue(
                orchestrator.addNode(
                                group.groupId(),
                                "follow-up",
                                "Continue the work",
                                "coding",
                                Set.of("n2"))
                        instanceof GroupOrchestrator.NodeEdit.Applied);
        Group resumed = requireGroup(orchestrator.tick(group.groupId()));
        assertEquals(Group.GroupState.ACTIVE, resumed.state());
        assertEquals(group.groupId(), resumed.groupId());
        assertEquals(group.mates(), resumed.mates());
        assertEquals(DagNode.NodeState.VERIFIED, findNode(resumed, "n2").state());
        assertEquals(DagNode.NodeState.RUNNING, findNode(resumed, "follow-up").state());

        registry.disband(group.groupId(), Instant.now());
        assertTrue(
                orchestrator.addNode(
                                group.groupId(),
                                "after-disband",
                                "Must not run",
                                "coding",
                                Set.of())
                        instanceof GroupOrchestrator.NodeEdit.Rejected);
    }

    @Test
    void reservedReviewerCannotBeReusedForItsOwnUpstreamTask() {
        Group group =
                Group.create(
                        "Leader-1",
                        "user-1",
                        "review",
                        blackboard,
                        new ExecutionDag(
                                UUID.randomUUID(),
                                List.of(DagNode.pending("source", "Produce", "coding", Set.of()))));
        registry.put(group);
        AtomicInteger next = new AtomicInteger();
        GroupOrchestrator withProvisioning =
                new GroupOrchestrator(
                        registry,
                        blackboard,
                        new HeuristicLeader(),
                        (id, skillset) -> "new-" + next.incrementAndGet());
        assertTrue(
                withProvisioning.addNode(
                                group.groupId(),
                                "review",
                                "Independent review",
                                "coding",
                                Set.of("source"),
                                null,
                                true)
                        instanceof GroupOrchestrator.NodeEdit.Applied);
        Group dispatched = requireGroup(withProvisioning.tick(group.groupId()));
        assertEquals("new-1", findNode(dispatched, "review").assignedMateId());
        assertEquals("new-2", findNode(dispatched, "source").assignedMateId());
        assertEquals(DagNode.NodeState.PENDING, findNode(dispatched, "review").state());
    }

    @Test
    void independentReviewCreatesAnotherMateDespiteMatchingIdleMember() {
        Group group = setupGroup();
        registry.put(group);
        GroupOrchestrator withProvisioning =
                new GroupOrchestrator(
                        registry,
                        blackboard,
                        new HeuristicLeader(),
                        (id, skillset) -> "independent-reviewer");
        assertTrue(
                withProvisioning.addNode(
                                group.groupId(),
                                "independent",
                                "Independent review",
                                "coding",
                                Set.of("n1"),
                                null,
                                true)
                        instanceof GroupOrchestrator.NodeEdit.Applied);
        Group registered = requireGroup(registry.get(group.groupId()));
        assertEquals("independent-reviewer", findNode(registered, "independent").assignedMateId());
        assertEquals(3, registered.mates().size());
        assertEquals(DagNode.NodeState.PENDING, findNode(registered, "independent").state());
        assertTrue(
                withProvisioning.addNode(
                                group.groupId(),
                                "ambiguous",
                                "Review",
                                "coding",
                                Set.of(),
                                "Mate-A",
                                true)
                        instanceof GroupOrchestrator.NodeEdit.Rejected);
        assertFalse(
                requireGroup(registry.get(group.groupId())).dag().nodeIds().contains("ambiguous"));
    }

    @Test
    void pinnedMateWaitsForItsWorkAndReceivesDependencyReport() {
        Group group = setupGroup();
        registry.put(group);
        orchestrator.tick(group.groupId());
        assertTrue(
                orchestrator.addNode(
                                group.groupId(),
                                "follow",
                                "Review previous result",
                                "different-label",
                                Set.of("n1"),
                                "Mate-B")
                        instanceof GroupOrchestrator.NodeEdit.Applied);
        assertTrue(
                orchestrator.addNode(
                                group.groupId(),
                                "unknown",
                                "Review",
                                "coding",
                                Set.of(),
                                "outsider")
                        instanceof GroupOrchestrator.NodeEdit.Rejected);
        GroupTestMessages.accept(blackboard, group.groupId(), "Mate-A", "n1");
        Group busy = requireGroup(orchestrator.tick(group.groupId()));
        assertEquals(DagNode.NodeState.RUNNING, findNode(busy, "n2").state());
        assertEquals(DagNode.NodeState.PENDING, findNode(busy, "follow").state());
        GroupTestMessages.accept(blackboard, group.groupId(), "Mate-B", "n2");
        Group ready = requireGroup(orchestrator.tick(group.groupId()));
        assertEquals("Mate-B", findNode(ready, "follow").assignedMateId());
        assertEquals(DagNode.NodeState.RUNNING, findNode(ready, "follow").state());
        var dispatch =
                blackboard.readFor(group.groupId(), "Mate-B").stream()
                        .filter(
                                m ->
                                        m.type() == BlackboardMessage.MessageType.TASK_DISPATCH
                                                && m.payload().startsWith("follow:"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(dispatch.payload().contains("Node: n1"));
        assertTrue(dispatch.payload().contains("Executed by Mate: Mate-A"));
        assertTrue(dispatch.payload().contains("Synthetic Mate completion."));
        assertFalse(dispatch.payload().contains("Node: n2"));
        assertFalse(dispatch.payload().contains("accept-base64"));
    }

    @Test
    void runningNodeCannotDisappearWhileItsMateIsExecuting() {
        Group group = setupGroup();
        registry.put(group);
        orchestrator.tick(group.groupId());
        GroupTestMessages.accept(blackboard, group.groupId(), "Mate-A", "n1");
        orchestrator.tick(group.groupId());
        assertTrue(
                orchestrator.removeNode(group.groupId(), "n2")
                        instanceof GroupOrchestrator.NodeEdit.Rejected);
        assertEquals(
                DagNode.NodeState.RUNNING,
                findNode(requireGroup(registry.get(group.groupId())), "n2").state());
    }

    @Test
    void malformedCompletionCannotUnlockDependentWork() {
        Group group = setupGroup();
        registry.put(group);
        orchestrator.tick(group.groupId());
        blackboard.post(
                new BlackboardMessage(
                        UUID.randomUUID().toString(),
                        group.groupId(),
                        "Mate-A",
                        "LEADER",
                        BlackboardMessage.MessageType.ACCEPT,
                        "n1:accept-base64:!!!",
                        0));
        Group result = requireGroup(orchestrator.tick(group.groupId()));
        assertEquals(DagNode.NodeState.FAILED, findNode(result, "n1").state());
        assertEquals(DagNode.NodeState.PENDING, findNode(result, "n2").state());
    }

    @Test
    void replanFailedReturnsNodeToPending() {
        Group g = setupGroup();
        registry.put(g);
        orchestrator.tick(g.groupId());
        GroupTestMessages.feedback(blackboard, g.groupId(), "Mate-A", "n1", "needs another pass");
        Group ticked = requireGroup(orchestrator.tick(g.groupId()));
        assertEquals(DagNode.NodeState.FAILED, findNode(ticked, "n1").state());

        // Leader re-plans: the failed node goes back to PENDING.
        Group replanned = requireGroup(orchestrator.replanFailed(g.groupId(), "n1"));
        assertEquals(DagNode.NodeState.PENDING, findNode(replanned, "n1").state());
        assertEquals(replanned, registry.get(g.groupId()));
        Group retried = requireGroup(orchestrator.tick(g.groupId()));
        assertEquals(1, findNode(retried, "n1").retryCount());
        assertTrue(
                blackboard.readFor(g.groupId(), "Mate-A").stream()
                        .anyMatch(
                                m ->
                                        m.payload()
                                                .contains(
                                                        "Previous attempt failed: needs another pass")));
        orchestrator.replanFailed(g.groupId(), "n1");
        assertEquals(
                DagNode.NodeState.RUNNING,
                findNode(requireGroup(registry.get(g.groupId())), "n1").state());
    }

    @Test
    void terminalStatusStopsRedispatchAndPreservesFailure() {
        Group group = setupGroup();
        registry.put(group);
        orchestrator.tick(group.groupId());
        blackboard.post(
                new BlackboardMessage(
                        UUID.randomUUID().toString(),
                        group.groupId(),
                        "Mate-A",
                        "LEADER",
                        BlackboardMessage.MessageType.STATUS,
                        "terminal:n1:breaker-trip",
                        0));
        Group ticked = requireGroup(orchestrator.tick(group.groupId()));
        assertEquals(DagNode.NodeState.FAILED, findNode(ticked, "n1").state());
        assertEquals(
                new DagNode.ResultFailure("breaker-trip", List.of()),
                findNode(ticked, "n1").result());
        orchestrator.tick(group.groupId());
        assertEquals(
                1,
                blackboard.readAll(group.groupId()).stream()
                        .filter(m -> m.type() == BlackboardMessage.MessageType.TASK_DISPATCH)
                        .count());
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
        GroupTestMessages.accept(blackboard, g.groupId(), "Mate-A", "n1");
        Group ticked = requireGroup(orchestrator.tick(g.groupId()));
        assertEquals(DagNode.NodeState.STALE, findNode(ticked, "n1").state());
    }

    @Test
    void acceptFromUnassignedSenderCannotVerifyNode() {
        Group g = setupGroup();
        registry.put(g);
        orchestrator.tick(g.groupId());

        GroupTestMessages.accept(blackboard, g.groupId(), "Mate-B", "n1");
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
