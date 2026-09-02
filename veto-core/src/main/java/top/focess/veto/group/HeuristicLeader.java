package top.focess.veto.group;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A deterministic stand-in for an LLM-backed Leader. It authors the DAG, chooses Mates, decides
 * when to escalate failed work, and triggers a strategic pivot using simple rules:
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
 * <p>An LLM-backed Leader can replace these rules with model decisions such as re-authoring the DAG
 * or choosing a retry strategy.
 */
@Component
public class HeuristicLeader {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.group.HeuristicLeader");

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
    public @NonNull Group assignMates(@NonNull Group group) {
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
    private String pickMate(@NonNull DagNode n, @NonNull Map<String, String> mates) {
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
        // This implementation returns the first candidate; an LLM-backed Leader can inspect live
        // assignment state before choosing.
        return candidates.get(0);
    }

    /**
     * Escalate on a FAILED node: if the node has been retried fewer than {@code maxRetries} times,
     * re-dispatch to the same Mate with the feedback. Otherwise, mark the node {@code STALE}
     * (Strategic Pivot — the engine will re-dispatch after re-plan).
     */
    public @NonNull Group escalate(
            @NonNull Group group, @NonNull String nodeId, @NonNull String feedback) {
        ExecutionDag dag = group.dag();
        for (DagNode n : dag.nodes()) {
            if (!n.nodeId().equals(nodeId)) {
                continue;
            }
            int retries = n.retryCount();
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
                                new DagNode.ResultFailure(feedback, List.of()),
                                retries + 1);
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
                            new DagNode.ResultFailure(feedback, List.of()),
                            retries + 1);
            return group.withDag(dag.withNode(nodeId, updated));
        }
        return group;
    }

    private int countRetries(@NonNull DagNode n) {
        // Use the persisted retryCount field on the node.
        return n.retryCount();
    }

    /**
     * Re-plan after a Strategic Pivot: nodes that are STALE get unassigned and put back to PENDING;
     * the engine will re-dispatch them via {@link #assignMates(Group)}.
     */
    public @NonNull Group replan(@NonNull Group group) {
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
     *   <li>any single Mate has more than {@code pivotThreshold} messages without an ACCEPT, which
     *       indicates progress deadlock, or
     *   <li>the group's active reasoning buffer is over the saturation threshold (Context
     *       Saturation).
     * </ul>
     */
    public boolean shouldPivot(
            @NonNull Group group, int perMateMessageCount, double contextSaturationRatio) {
        return perMateMessageCount > pivotThreshold || contextSaturationRatio > 0.8;
    }

    /**
     * Apply a Strategic Pivot: re-plan the DAG (STALE → PENDING with unassigned Mate) and let
     * {@link #assignMates(Group)} re-assign on the next tick. This is the entry point the {@link
     * GroupOrchestrator} calls when {@link #shouldPivot} returns true.
     */
    public @NonNull Group pivot(@NonNull Group group) {
        log.info("HeuristicLeader: pivoting group {}", group.groupId());
        return replan(group);
    }

    /**
     * Compute the context saturation ratio for a group. This is a heuristic measure of how "full"
     * the group's reasoning context is, based on the number of messages, artifacts, and DAG nodes.
     * This implementation returns a simple ratio based on DAG size; an LLM-backed Leader could use
     * token counting or embedding-based similarity.
     *
     * @return a value between 0.0 (empty) and 1.0 (saturated)
     */
    public double contextSaturation(@NonNull Group group) {
        ExecutionDag dag = group.dag();
        int nodeCount = dag.nodes().size();
        // Simple heuristic: assume saturation at about 20 nodes. A model-aware implementation could
        // count tokens in the Blackboard and DAG.
        double ratio = nodeCount / 20.0;
        return Math.min(1.0, ratio);
    }

    /** Test accessor: return the per-Mate pivot threshold for unit tests. */
    public int perMateThresholdForTest() {
        return pivotThreshold;
    }
}
