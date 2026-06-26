package top.focess.veto.group;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A single node in the group's Execution DAG (execution_dag.md). Each node is a unit of work
 * assigned to a single Mate, with explicit dependencies on other nodes.
 */
public record DagNode(
        String nodeId,
        String description,
        String assignedMateId, // null if unassigned
        String requiredSkillset, // e.g. "coding", "testing", "graphql"
        Set<String> dependsOn, // nodeIds
        NodeState state,
        NodeResult result) {

    public DagNode {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(requiredSkillset, "requiredSkillset");
        dependsOn = dependsOn == null ? Set.of() : Set.copyOf(dependsOn);
        if (state == null) {
            state = NodeState.PENDING;
        }
    }

    public enum NodeState {
        PENDING, // not yet dispatched
        RUNNING, // dispatched to a Mate
        VERIFIED, // a testing Mate accepted the artifact
        FAILED, // verifier rejected; awaits Leader re-plan
        STALE // superseded (Mate removed / re-assigned); not to be re-dispatched
    }

    public sealed interface NodeResult permits ResultNone, ResultArtifact, ResultFailure {}

    /** Default: no result yet. */
    public record ResultNone() implements NodeResult {}

    /** Verifier accepted: an artifact was produced at the given workspace path. */
    public record ResultArtifact(String artifactPath) implements NodeResult {}

    /** Verifier (or self) reported failure with a short feedback string. */
    public record ResultFailure(String feedback, List<String> logRefs) implements NodeResult {
        public ResultFailure {
            logRefs = logRefs == null ? List.of() : List.copyOf(logRefs);
        }
    }

    public static DagNode pending(
            String nodeId, String description, String requiredSkillset, Set<String> dependsOn) {
        return new DagNode(
                nodeId,
                description,
                null,
                requiredSkillset,
                dependsOn,
                NodeState.PENDING,
                new ResultNone());
    }
}
