package top.focess.veto.group;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The plan for a group's work (execution_dag.md). An ordered list of {@link DagNode}s with explicit
 * dependencies. The Leader authors the DAG; the group engine drives the nodes through their state
 * machine.
 *
 * <p>Topological order is implicit in the dependency graph: a node is dispatchable iff all of its
 * {@code dependsOn} are in {@link DagNode.NodeState#VERIFIED}.
 */
public record ExecutionDag(UUID groupId, List<DagNode> nodes) {

    public ExecutionDag {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(nodes, "nodes");
        nodes = List.copyOf(nodes);
    }

    /**
     * Return the nodes currently dispatchable (all deps VERIFIED) and not yet dispatched or
     * terminal. A node is dispatchable when every node in {@code dependsOn} is VERIFIED.
     */
    public List<DagNode> dispatchable() {
        Map<String, DagNode> byId = index();
        List<DagNode> out = new ArrayList<>();
        for (DagNode n : nodes) {
            if (n.state() != DagNode.NodeState.PENDING) {
                continue;
            }
            boolean ready = true;
            for (String dep : n.dependsOn()) {
                DagNode d = byId.get(dep);
                if (d == null || d.state() != DagNode.NodeState.VERIFIED) {
                    ready = false;
                    break;
                }
            }
            if (ready) {
                out.add(n);
            }
        }
        return out;
    }

    /** Nodes currently in {@code RUNNING} state. */
    public List<DagNode> running() {
        return nodes.stream().filter(n -> n.state() == DagNode.NodeState.RUNNING).toList();
    }

    /** Returns a copy of the DAG with the given node updated. */
    public ExecutionDag withNode(String nodeId, DagNode updated) {
        List<DagNode> next = new ArrayList<>();
        for (DagNode n : nodes) {
            if (n.nodeId().equals(nodeId)) {
                next.add(updated);
            } else {
                next.add(n);
            }
        }
        return new ExecutionDag(groupId, next);
    }

    /** Returns a copy of the DAG with the given node list (for bulk updates from the Leader). */
    public ExecutionDag withNodes(List<DagNode> newNodes) {
        return new ExecutionDag(groupId, newNodes);
    }

    /** Returns the set of node ids in the DAG. */
    public Set<String> nodeIds() {
        return index().keySet();
    }

    private Map<String, DagNode> index() {
        Map<String, DagNode> m = new LinkedHashMap<>();
        for (DagNode n : nodes) {
            m.put(n.nodeId(), n);
        }
        return m;
    }

    /** Convenience: build a DAG from a list of node ids + dependencies. */
    public static ExecutionDag linear(UUID groupId, List<String> nodeIds) {
        if (nodeIds.isEmpty()) {
            return new ExecutionDag(groupId, List.of());
        }
        List<DagNode> nodes = new ArrayList<>();
        for (int i = 0; i < nodeIds.size(); i++) {
            String id = nodeIds.get(i);
            Set<String> deps = i == 0 ? Set.of() : Set.of(nodeIds.get(i - 1));
            nodes.add(DagNode.pending(id, "auto-generated node " + id, "coding", deps));
        }
        return new ExecutionDag(groupId, nodes);
    }
}
