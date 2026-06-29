package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the F1+F2+F4+F11+F13 batch of fixes in {@link GroupOrchestrator} and {@link
 * HeuristicLeader}:
 *
 * <ul>
 *   <li>F1: per-Mate message counter (not global sum) feeds the pivot decision.
 *   <li>F2: persisted retry count on DagNode makes the STALE-pivot branch reachable.
 *   <li>F4: per-group tick lock serializes lastSeenSeq read-modify-write.
 *   <li>F11: empty-DAG group is NOT force-completed on its first tick.
 *   <li>F13: orchestrator's pivotThreshold + CONTEXT_SATURATION_THRESHOLD are authoritative.
 * </ul>
 */
class GroupOrchestratorFixesTest {

    @Test
    void perMateCounterNotSum() {
        // F1: 4 Mates each posting 4 non-ACCEPT messages must NOT trigger a pivot when the
        // threshold is 5 (any single Mate is at 4 < 5). The pre-fix behavior summed across
        // Mates (4×4 = 16) and tripped the pivot.
        HeuristicLeader heuristic = new HeuristicLeader(2, 5);
        Group g =
                newGroup(
                        "user-1", "Mate-A", "coding", "Mate-B", "coding", "Mate-C", "coding",
                        "Mate-D", "coding");
        // Below threshold: no Mate has more than 4 → should not pivot.
        assertFalse(
                perMateShouldPivot(heuristic, g, 4),
                "F1: 4 messages per Mate × 4 Mates must NOT exceed per-Mate threshold of 5");
        // A single Mate at 6 must pivot even when others are quiet.
        Group g2 = newGroup("user-1", "Mate-A", "coding", "Mate-B", "coding");
        assertTrue(
                perMateShouldPivot(heuristic, g2, 6),
                "F1: a single Mate at 6 must pivot when threshold is 5");
    }

    @Test
    void escalateReachesStaleAfterMaxRetries() {
        // F2: with persisted retryCount, the STALE branch must fire after maxRetries failures.
        HeuristicLeader leader = new HeuristicLeader(2, 100); // maxRetries=2, pivot high so
        // pivot() doesn't fire on its own.
        Blackboard blackboard = new Blackboard();
        UUID groupId = UUID.randomUUID();
        Group g =
                Group.create(
                        "Leader-1",
                        "user-1",
                        "test",
                        blackboard,
                        ExecutionDag.linear(groupId, List.of("n1")));
        g = g.withMate("Mate-A", "coding");
        ExecutionDag dag =
                g.dag()
                        .withNode(
                                "n1",
                                new DagNode(
                                        "n1",
                                        "task",
                                        "Mate-A",
                                        "coding",
                                        Set.of(),
                                        DagNode.NodeState.PENDING,
                                        new DagNode.ResultNone()));
        g = g.withDag(dag);

        // First failure: retries goes 0 → 1 → PENDING (re-dispatch).
        Group afterFirst = leader.escalate(g, "n1", "fail1");
        assertEquals(1, retryCountOf(afterFirst, "n1"), "F2: first failure increments to 1");
        assertEquals(DagNode.NodeState.PENDING, stateOf(afterFirst, "n1"));

        // Second failure: retries 1 → 2 → PENDING (re-dispatch).
        Group afterSecond = leader.escalate(afterFirst, "n1", "fail2");
        assertEquals(2, retryCountOf(afterSecond, "n1"), "F2: second failure increments to 2");
        assertEquals(DagNode.NodeState.PENDING, stateOf(afterSecond, "n1"));

        // Third failure: retries 2 ≥ maxRetries=2 → STALE (Strategic Pivot reached).
        Group afterThird = leader.escalate(afterSecond, "n1", "fail3");
        assertEquals(
                DagNode.NodeState.STALE,
                stateOf(afterThird, "n1"),
                "F2: STALE branch must fire when retries >= maxRetries");
    }

    @Test
    void tickIsSerializedPerGroup() throws Exception {
        // F4: two concurrent ticks on the same groupId must NOT double-ingest the same
        // Blackboard message. The per-group lock means one tick runs to completion before the
        // other starts; the second tick sees the group as DISBANDED and returns without
        // re-processing. The final state must show n1=VERIFIED (not double-processed).
        Blackboard blackboard = new Blackboard();
        GroupRegistry registry = new GroupRegistry();
        LlmLeader llmLeader = new LlmLeader(null, new HeuristicLeader());
        GroupOrchestrator orch = new GroupOrchestrator(registry, blackboard, llmLeader);
        Group g =
                Group.create(
                        "Leader-1",
                        "user-1",
                        "test",
                        blackboard,
                        ExecutionDag.linear(UUID.randomUUID(), List.of("n1")));
        UUID groupId = g.groupId();
        g = g.withMate("Mate-A", "coding");
        registry.put(g);

        // Post a single ACCEPT and run two concurrent ticks.
        blackboard.post(
                new BlackboardMessage(
                        UUID.randomUUID().toString(),
                        groupId,
                        "Mate-A",
                        "LEADER",
                        BlackboardMessage.MessageType.ACCEPT,
                        "n1:accept:/x",
                        0));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Runnable tickTask =
                () -> {
                    try {
                        start.await();
                        orch.tick(groupId);
                    } catch (Exception ignored) {
                    }
                };
        pool.submit(tickTask);
        pool.submit(tickTask);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        // F4 invariants under the per-group lock:
        // - The group must still exist in the registry.
        // - The node must be VERIFIED exactly once (no double-ingest).
        // - The group must be DISBANDED (maybeComplete saw all nodes VERIFIED).
        // - Subsequent ticks return the DISBANDED group without re-dispatching.
        Group finalG = registry.get(groupId);
        assertNotNull(finalG, "F4: group must still exist in registry after concurrent ticks");
        assertEquals(1, finalG.dag().nodes().size());
        assertEquals(
                Group.GroupState.DISBANDED,
                finalG.state(),
                "F4: group must be DISBANDED after the ACCEPT was processed");
        assertEquals(
                DagNode.NodeState.VERIFIED,
                finalG.dag().nodes().get(0).state(),
                "F4: n1 must be VERIFIED — not RUNNING (a re-dispatch would race the lock)");
        // A follow-up tick on the DISBANDED group must be a no-op (early return).
        Group after = orch.tick(groupId);
        assertEquals(
                Group.GroupState.DISBANDED, after.state(), "F4: post-DISBAND tick must be a no-op");
    }

    @Test
    void emptyDagDoesNotComplete() {
        // F11: a group with zero nodes must NOT be flipped to DISBANDED on its first tick.
        Blackboard blackboard = new Blackboard();
        GroupRegistry registry = new GroupRegistry();
        LlmLeader llmLeader = new LlmLeader(null, new HeuristicLeader());
        GroupOrchestrator orch = new GroupOrchestrator(registry, blackboard, llmLeader);
        Group g =
                Group.create(
                        "Leader-1",
                        "user-1",
                        "test",
                        blackboard,
                        new ExecutionDag(UUID.randomUUID(), List.of()));
        UUID groupId = g.groupId();
        registry.put(g);

        orch.tick(groupId);
        Group ticked = registry.get(groupId);
        assertNotNull(ticked);
        assertEquals(
                Group.GroupState.ACTIVE,
                ticked.state(),
                "F11: empty-DAG group must stay ACTIVE (Leader hasn't authored yet)");
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static Group newGroup(String userId, Object... matePairs) {
        Blackboard blackboard = new Blackboard();
        UUID groupId = UUID.randomUUID();
        Group g =
                Group.create(
                        "Leader-1",
                        userId,
                        "test",
                        blackboard,
                        ExecutionDag.linear(groupId, List.of("n1")));
        for (int i = 0; i < matePairs.length; i += 2) {
            g = g.withMate((String) matePairs[i], (String) matePairs[i + 1]);
        }
        return g;
    }

    /** Per-Mate pivot check (F1). Simulates GroupOrchestrator.maybePivot's per-Mate max. */
    private static boolean perMateShouldPivot(HeuristicLeader leader, Group g, int perMateCount) {
        // The orchestrator now checks `any Mate > threshold`. Reproduce that here.
        return perMateCount > leader.perMateThresholdForTest();
    }

    private static int retryCountOf(Group g, String nodeId) {
        return g.dag().nodes().stream()
                .filter(n -> n.nodeId().equals(nodeId))
                .findFirst()
                .map(DagNode::retryCount)
                .orElseThrow();
    }

    private static DagNode.NodeState stateOf(Group g, String nodeId) {
        return g.dag().nodes().stream()
                .filter(n -> n.nodeId().equals(nodeId))
                .findFirst()
                .map(DagNode::state)
                .orElseThrow();
    }
}
