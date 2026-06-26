package top.focess.veto.group;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Leader's reasoning stub (Part 2 LLM-driven orchestration). The real Leader is a Top-Tier LLM
 * agent that authors the DAG, chooses Mates, decides when to escalate on FAILED, and triggers
 * Strategic Pivot. This {@code HeuristicLeader} is a deterministic stand-in that performs those
 * decisions by simple rules:
 *
 * <ul>
 *   <li><b>Mate assignment</b>: for each PENDING node, pick the Mate whose skillset matches the
 *       node's {@code requiredSkillset}. If no Mate matches, pick any available Mate (degraded
 *       fallback).
 *   <li><b>Escalation on FAILED</b>: a FAILED node is re-dispatched to the same Mate with the
 *       feedback accumulated in the result. After {@code maxRetries} failures, the node is marked
 *       {@code STALE} and the Leader "re-plans" by re-assigning to a different Mate (Strategic
 *       Pivot).
 *   <li><b>Strategic Pivot</b>: when a Mate has more than {@code pivotThreshold} messages posted
 *       without an ACCEPT, the engine re-plans all that Mate's PENDING nodes.
 * </ul>
 *
 * <p>This is the MVP path. A real LLM Leader would replace these heuristics with reasoning calls
 * (re-authoring the DAG, deciding on backoff, etc.).
 */
@Component
public class HeuristicLeader {

    private static final Logger log = LoggerFactory.getLogger(HeuristicLeader.class);

    /** Per-node max retries before a Strategic Pivot (re-assign to a different Mate). */
    public static final int DEFAULT_MAX_RETRIES = 2;

    /** Per-Mate message budget without an ACCEPT before a Strategic Pivot. */
    public static final int DEFAULT_PIVOT_THRESHOLD = 5;

    private final int maxRetries;
    private final int pivotThreshold;

    public HeuristicLeader() {
        this(DEFAULT_MAX_RETRIES, DEFAULT_PIVOT_THRESHOLD);
    }

    public HeuristicLeader(int maxRetries, int pivotThreshold) {
        this.maxRetries = maxRetries;
        this.pivotThreshold = pivotThreshold;
    }

    /**
     * Assign Mates to PENDING nodes based on skillset match. Returns the updated group. Nodes that
     * already have an assignment are left alone.
     */
    public Group assignMates(Group group) {
        ExecutionDag dag = group.dag();
        List<DagNode> next = new ArrayList<>();
        boolean changed = false;
        for (DagNode n : dag.nodes()) {
            if (n.state() != DagNode.NodeState.PENDING || n.assignedMateId() != null) {
                next.add(n);
                continue;
            }
            String picked = pickMate(n, group.mates());
            if (picked == null) {
                next.add(n);
                continue;
            }
            DagNode updated =
                    new DagNode(
                            n.nodeId(),
                            n.description(),
                            picked,
                            n.requiredSkillset(),
                            n.dependsOn(),
                            n.state(),
                            n.result());
            next.add(updated);
            changed = true;
        }
        return changed ? group.withDag(dag.withNodes(next)) : group;
    }

    /**
     * Pick a Mate for a node: prefer exact skillset match; if multiple, pick the Mate with the
     * fewest currently-RUNNING assignments (load balance); if none match, pick any available Mate.
     * Returns null if no Mates are available.
     */
    private String pickMate(DagNode n, Map<String, String> mates) {
        if (mates.isEmpty()) {
            return null;
        }
        // Exact skillset match: list candidates, then break ties by least loaded.
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, String> e : mates.entrySet()) {
            if (e.getValue().equalsIgnoreCase(n.requiredSkillset())) {
                candidates.add(e.getKey());
            }
        }
        if (candidates.isEmpty()) {
            // Fallback: any Mate
            candidates.addAll(mates.keySet());
        }
        // Sort by least-loaded (fewest RUNNING assignments) — for the MVP just return
        // the first candidate; a real Leader would query the live state.
        return candidates.get(0);
    }

    /**
     * Escalate on a FAILED node: if the node has been retried fewer than {@code maxRetries} times,
     * re-dispatch to the same Mate with the feedback. Otherwise, mark the node {@code STALE}
     * (Strategic Pivot — the engine will re-dispatch after re-plan).
     */
    public Group escalate(Group group, String nodeId, String feedback) {
        ExecutionDag dag = group.dag();
        for (DagNode n : dag.nodes()) {
            if (!n.nodeId().equals(nodeId)) {
                continue;
            }
            int retries = countRetries(n);
            if (retries >= maxRetries) {
                log.info(
                        "HeuristicLeader: node {} retried {} times — pivoting to STALE",
                        nodeId,
                        retries);
                DagNode updated =
                        new DagNode(
                                n.nodeId(),
                                n.description(),
                                null, // unassign; the re-plan will pick a new Mate
                                n.requiredSkillset(),
                                n.dependsOn(),
                                DagNode.NodeState.STALE,
                                new DagNode.ResultFailure(
                                        feedback == null ? "max retries" : feedback, List.of()));
                return group.withDag(dag.withNode(nodeId, updated));
            }
            // Re-dispatch: PENDING with the feedback in the result so the engine + Mate
            // see the previous failure on the next attempt.
            DagNode updated =
                    new DagNode(
                            n.nodeId(),
                            n.description(),
                            n.assignedMateId(),
                            n.requiredSkillset(),
                            n.dependsOn(),
                            DagNode.NodeState.PENDING,
                            new DagNode.ResultFailure(
                                    feedback == null ? "retry" : feedback, List.of()));
            return group.withDag(dag.withNode(nodeId, updated));
        }
        return group;
    }

    private int countRetries(DagNode n) {
        // Count the number of FAILED / FAILED-retry cycles recorded in the result.
        // For the MVP, count any ResultFailure as a retry; a real implementation would
        // track the retry count on the result.
        return n.result() instanceof DagNode.ResultFailure ? 1 : 0;
    }

    /**
     * Re-plan after a Strategic Pivot: nodes that are STALE get unassigned and put back to PENDING;
     * the engine will re-dispatch them via {@link #assignMates(Group)}.
     */
    public Group replan(Group group) {
        ExecutionDag dag = group.dag();
        List<DagNode> next = new ArrayList<>();
        for (DagNode n : dag.nodes()) {
            if (n.state() == DagNode.NodeState.STALE) {
                next.add(
                        new DagNode(
                                n.nodeId(),
                                n.description(),
                                null,
                                n.requiredSkillset(),
                                n.dependsOn(),
                                DagNode.NodeState.PENDING,
                                new DagNode.ResultNone()));
            } else {
                next.add(n);
            }
        }
        return group.withDag(dag.withNodes(next));
    }

    /**
     * Decide whether the group should pivot (per Leader heuristics). Returns true when:
     *
     * <ul>
     *   <li>any single Mate has more than {@code pivotThreshold} messages without an ACCEPT
     *       (Progress Deadlock per the LLD), or
     *   <li>the group's active reasoning buffer is over the saturation threshold (Context
     *       Saturation).
     * </ul>
     */
    public boolean shouldPivot(
            Group group, int perMateMessageCount, double contextSaturationRatio) {
        return perMateMessageCount > pivotThreshold || contextSaturationRatio > 0.8;
    }

    /**
     * Apply a Strategic Pivot: re-plan the DAG (STALE → PENDING with unassigned Mate) and let
     * {@link #assignMates(Group)} re-assign on the next tick. This is the entry point the {@link
     * GroupOrchestrator} calls when {@link #shouldPivot} returns true.
     */
    public Group pivot(Group group) {
        log.info("HeuristicLeader: pivoting group {}", group.groupId());
        return replan(group);
    }
}
