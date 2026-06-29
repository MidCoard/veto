package top.focess.veto.group;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A single node in the group's Execution DAG (execution_dag.md). Each node is a unit of work
 * assigned to a single Mate, with explicit dependencies on other nodes.
 */
public record DagNode(
        @NotNull String nodeId,
        @NotNull String description,
        @Nullable String assignedMateId, // null if unassigned
        @NotNull String requiredSkillset, // e.g. "coding", "testing", "graphql"
        @NotNull Set<String> dependsOn, // nodeIds
        @NotNull NodeState state,
        @NotNull NodeResult result,
        int retryCount) { // number of FAILED → retry cycles

    public DagNode {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(requiredSkillset, "requiredSkillset");
        dependsOn = dependsOn == null ? Set.of() : Set.copyOf(dependsOn);
        if (state == null) {
            state = NodeState.PENDING;
        }
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

    public sealed interface NodeResult permits ResultNone, ResultArtifact, ResultFailure {}

    /** Default: no result yet. */
    public record ResultNone() implements NodeResult {}

    /** Verifier accepted: an artifact was produced at the given workspace path. */
    public record ResultArtifact(@NotNull String artifactPath) implements NodeResult {}

    /** Verifier (or self) reported failure with a short feedback string. */
    public record ResultFailure(@NotNull String feedback, @NotNull List<String> logRefs)
            implements NodeResult {
        public ResultFailure {
            logRefs = logRefs == null ? List.of() : List.copyOf(logRefs);
        }
    }

    @NotNull
    public static DagNode pending(
            @NotNull String nodeId,
            @NotNull String description,
            @NotNull String requiredSkillset,
            @NotNull Set<String> dependsOn) {
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
            @NotNull String nodeId,
            @NotNull String description,
            @Nullable String assignedMateId,
            @NotNull String requiredSkillset,
            @NotNull Set<String> dependsOn,
            @NotNull NodeState state,
            @NotNull NodeResult result) {
        this(nodeId, description, assignedMateId, requiredSkillset, dependsOn, state, result, 0);
    }
}
