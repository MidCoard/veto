package top.focess.veto.group;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** User-facing node results; completion does not imply independent verification. */
public record GroupHistoryView(
        @NonNull String id,
        @NonNull String leaderId,
        @NonNull String brief,
        @NonNull String state,
        @NonNull Instant createdAt,
        @NonNull List<Node> nodes,
        boolean historical,
        boolean live,
        @NonNull List<Change> changes) {
    public record Node(
            @NonNull String id,
            @NonNull String description,
            String mateId,
            @NonNull String skillset,
            @NonNull List<String> dependencies,
            @NonNull String state,
            @NonNull String report,
            int retries) {}

    public record Change(@NonNull Instant at, @NonNull String state, @NonNull List<Node> nodes) {}

    public static @NonNull List<Node> nodes(@NonNull Group group) {
        return group.dag().nodes().stream()
                .map(
                        n ->
                                new Node(
                                        n.nodeId(),
                                        n.description(),
                                        n.assignedMateId(),
                                        n.requiredSkillset(),
                                        List.copyOf(n.dependsOn()),
                                        n.state() == DagNode.NodeState.VERIFIED
                                                ? "COMPLETED"
                                                : n.state().name(),
                                        n.result() instanceof DagNode.ResultSuccess r
                                                ? r.summary()
                                                : n.result() instanceof DagNode.ResultFailure r
                                                        ? r.feedback()
                                                        : "",
                                        n.retryCount()))
                .toList();
    }
}
