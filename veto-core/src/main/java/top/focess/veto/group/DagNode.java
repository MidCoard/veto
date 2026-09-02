package top.focess.veto.group;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * A single node in the group's execution DAG. Each node is a unit of work assigned to one Mate,
 * with explicit dependencies on other nodes.
 */
public record DagNode(
        @NonNull String nodeId,
        @NonNull String description,
        String assignedMateId, // null if unassigned
        @NonNull String requiredSkillset, // e.g. "coding", "testing", "graphql"
        @NonNull Set<String> dependsOn, // nodeIds
        @NonNull NodeState state,
        @NonNull NodeResult result,
        int retryCount) { // number of FAILED → retry cycles

    public DagNode {
        dependsOn = Set.copyOf(dependsOn);
        if (retryCount < 0) {
            retryCount = 0;
        }
    }

    public enum NodeState {
        PENDING, // not yet dispatched
        RUNNING, // dispatched to a Mate
        VERIFIED, // a testing Mate accepted the artifact
        FAILED, // verifier rejected; awaits Leader re-plan
        STALE // superseded (Mate removed / re-assigned); not to be re-dispatched
    }

    public sealed interface NodeResult
            permits ResultNone, ResultSuccess, ResultArtifact, ResultFailure {}

    /** Default: no result yet. */
    public record ResultNone() implements NodeResult {}

    /** A Mate completed the node and returned a real textual report. */
    public record ResultSuccess(@NonNull String summary) implements NodeResult {}

    /** Verifier accepted: an artifact was produced at the given workspace path. */
    public record ResultArtifact(@NonNull String artifactPath) implements NodeResult {}

    /** Verifier (or self) reported failure with a short feedback string. */
    public record ResultFailure(@NonNull String feedback, @NonNull List<String> logRefs)
            implements NodeResult {
        public ResultFailure {
            logRefs = List.copyOf(logRefs);
        }
    }

    public static @NonNull DagNode pending(
            @NonNull String nodeId,
            @NonNull String description,
            @NonNull String requiredSkillset,
            @NonNull Set<String> dependsOn) {
        return new DagNode(
                nodeId,
                description,
                null,
                requiredSkillset,
                dependsOn,
                NodeState.PENDING,
                new ResultNone(),
                0);
    }

    /** Compatibility constructor for legacy code that doesn't specify retryCount. Defaults to 0. */
    public DagNode(
            @NonNull String nodeId,
            @NonNull String description,
            String assignedMateId,
            @NonNull String requiredSkillset,
            @NonNull Set<String> dependsOn,
            @NonNull NodeState state,
            @NonNull NodeResult result) {
        this(nodeId, description, assignedMateId, requiredSkillset, dependsOn, state, result, 0);
    }
}
