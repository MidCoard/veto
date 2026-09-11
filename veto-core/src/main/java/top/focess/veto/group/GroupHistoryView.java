package top.focess.veto.group;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        @NonNull List<Change> changes,
        Map<String, String> mates) {
    public GroupHistoryView {
        mates = mates == null ? null : Map.copyOf(mates);
    }

    /** Older snapshots have an unknown roster, which differs from an empty team. */
    public GroupHistoryView(
            @NonNull String id,
            @NonNull String leaderId,
            @NonNull String brief,
            @NonNull String state,
            @NonNull Instant createdAt,
            @NonNull List<Node> nodes,
            boolean historical,
            boolean live,
            @NonNull List<Change> changes) {
        this(id, leaderId, brief, state, createdAt, nodes, historical, live, changes, null);
    }

    public @NonNull GroupHistoryView withoutRuntime() {
        if (live || (!state.equals("ACTIVE") && !state.equals("RECOVERING"))) return this;
        List<Node> interrupted =
                nodes.stream()
                        .map(
                                node -> {
                                    if (!List.of("PENDING", "RUNNING", "CANCEL_REQUESTED")
                                            .contains(node.state())) return node;
                                    return new Node(
                                            node.id(),
                                            node.description(),
                                            node.mateId(),
                                            node.skillset(),
                                            node.dependencies(),
                                            "INTERRUPTED",
                                            "Runtime unavailable; this task was not replayed. Previous state: "
                                                    + node.state()
                                                    + ". Execution outcome is unknown; inspect completed side effects before resuming.",
                                            node.retries(),
                                            node.requestId(),
                                            node.dispatchId());
                                })
                        .toList();
        return new GroupHistoryView(
                id,
                leaderId,
                brief,
                "INTERRUPTED",
                createdAt,
                interrupted,
                historical,
                false,
                changes,
                mates);
    }

    public record Node(
            @NonNull String id,
            @NonNull String description,
            String mateId,
            @NonNull String skillset,
            @NonNull List<String> dependencies,
            @NonNull String state,
            @NonNull String report,
            int retries,
            String requestId,
            String dispatchId) {
        public Node(
                @NonNull String id,
                @NonNull String description,
                String mateId,
                @NonNull String skillset,
                @NonNull List<String> dependencies,
                @NonNull String state,
                @NonNull String report,
                int retries) {
            this(
                    id,
                    description,
                    mateId,
                    skillset,
                    dependencies,
                    state,
                    report,
                    retries,
                    null,
                    null);
        }
    }

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
                                        n.retryCount(),
                                        n.requestId(),
                                        n.dispatchId()))
                .toList();
    }
}
