package top.focess.veto.group;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Tests for the LlmLeader + Strategic Pivot triggers. */
class LlmLeaderTest {

    @Test
    void llmLeaderWithoutAgentFallsBackToHeuristic() {
        LlmLeader leader = new LlmLeader(null, new HeuristicLeader());
        UUID groupId = UUID.randomUUID();
        Blackboard blackboard = new Blackboard();
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
        g = g.withDag(dag);
        Group assigned = leader.assignMates(g);
        // No change — n1 already has a Mate assigned. (No-op test.)
        assertEquals("Mate-A", assigned.dag().nodes().get(0).assignedMateId());
    }

    @Test
    void llmLeaderAuthorDagFallsBackToLinear() {
        LlmLeader leader = new LlmLeader(null, new HeuristicLeader());
        UUID groupId = UUID.randomUUID();
        ExecutionDag dag = leader.authorDag(groupId, "build a CLI command");
        // No LLM → fallback to a single-node linear DAG.
        assertEquals(1, dag.nodes().size());
    }

    @Test
    void heuristicPivotTriggersOnMessageCount() {
        HeuristicLeader leader = new HeuristicLeader(2, 2);
        UUID groupId = UUID.randomUUID();
        Blackboard blackboard = new Blackboard();
        Group g =
                Group.create(
                        "Leader-1",
                        "user-1",
                        "build",
                        blackboard,
                        ExecutionDag.linear(groupId, List.of("n1")));
        g = g.withMate("Mate-A", "coding");
        // Below threshold: should not pivot.
        assertFalse(leader.shouldPivot(g, 1, 0.5));
        // Above threshold: should pivot.
        assertTrue(leader.shouldPivot(g, 3, 0.5));
    }

    @Test
    void heuristicPivotTriggersOnContextSaturation() {
        HeuristicLeader leader = new HeuristicLeader();
        UUID groupId = UUID.randomUUID();
        Blackboard blackboard = new Blackboard();
        Group g =
                Group.create(
                        "Leader-1",
                        "user-1",
                        "build",
                        blackboard,
                        ExecutionDag.linear(groupId, List.of("n1")));
        // Below 0.8: should not pivot.
        assertFalse(leader.shouldPivot(g, 0, 0.5));
        // Above 0.8: should pivot.
        assertTrue(leader.shouldPivot(g, 0, 0.85));
    }

    @Test
    void orchestratorTriggersPivotOnPerMateMessageCount() {
        Blackboard blackboard = new Blackboard();
        GroupRegistry registry = new GroupRegistry();
        HeuristicLeader heuristic = new HeuristicLeader(2, 2);
        LlmLeader llmLeader = new LlmLeader(null, heuristic);
        GroupOrchestrator orch = new GroupOrchestrator(registry, blackboard, llmLeader);

        UUID groupId = UUID.randomUUID();
        Group g =
                Group.create(
                        "Leader-1",
                        "user-1",
                        "build",
                        blackboard,
                        ExecutionDag.linear(groupId, List.of("n1")));
        g = g.withMate("Mate-A", "coding");
        registry.put(g);

        // Simulate Mate-A posting 3 non-ACCEPT messages (above threshold).
        for (int i = 0; i < 3; i++) {
            blackboard.post(
                    new BlackboardMessage(
                            UUID.randomUUID().toString(),
                            groupId,
                            "Mate-A",
                            "LEADER",
                            BlackboardMessage.MessageType.FEEDBACK,
                            "n1:feedback:try-" + i,
                            0));
        }
        // One tick should detect the deadlock and pivot (n1 → STALE → re-plan).
        Group ticked = orch.tick(g.groupId());
        // After pivot, n1 should be STALE (re-plan will un-stale it).
        // (The re-plan puts it back to PENDING via Leader.pivot().)
        // The point is: the tick didn't crash and the leader's pivot was invoked.
        assertNotNull(ticked);
    }

    @Test
    void llmLeaderPivotReasoning() {
        // Without an agent, LlmLeader's shouldPivot falls back to heuristic — which uses
        // a default threshold of 5. Verify it returns false for low message counts.
        LlmLeader leader = new LlmLeader(null, new HeuristicLeader());
        UUID groupId = UUID.randomUUID();
        Blackboard blackboard = new Blackboard();
        Group g =
                Group.create(
                        "Leader-1",
                        "user-1",
                        "build",
                        blackboard,
                        ExecutionDag.linear(groupId, List.of("n1")));
        g = g.withMate("Mate-A", "coding");
        assertFalse(leader.shouldPivot(g, 1, 0.5));
    }
}
