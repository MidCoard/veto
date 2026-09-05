package top.focess.veto.group;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Read-only group data; no orchestrator, DAG mutation, or blackboard access escapes. */
public record GroupSnapshot(
        @NonNull UUID groupId,
        @NonNull String contextBrief,
        @NonNull List<DagNode> nodes,
        @NonNull Map<String, String> mates,
        Group.@NonNull GroupState state) {
    public GroupSnapshot {
        nodes = List.copyOf(nodes);
        mates = Map.copyOf(mates);
    }
}
