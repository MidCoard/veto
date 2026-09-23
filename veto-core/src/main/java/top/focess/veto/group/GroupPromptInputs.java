package top.focess.veto.group;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.group.DagNode;
import top.focess.veto.api.group.GroupSnapshot;

/** Snapshot facts supplied to group MDC sources. */
final class GroupPromptInputs {
    private GroupPromptInputs() {}

    static @NonNull Map<String, Object> snapshot(GroupSnapshot group) {
        if (group == null) return Map.of("available", false);
        return Map.of(
                "available",
                true,
                "id",
                group.groupId(),
                "brief",
                group.contextBrief(),
                "state",
                group.state().name(),
                "nodes",
                group.nodes().stream().map(GroupPromptInputs::node).toList(),
                "mates",
                group.mates().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(
                                entry ->
                                        Map.of(
                                                "id",
                                                entry.getKey(),
                                                "responsibility",
                                                entry.getValue()))
                        .toList());
    }

    static @NonNull Map<String, Object> node(@NonNull DagNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        String assigned = node.assignedMateId();
        result.put("id", node.nodeId());
        result.put("state", node.state().name());
        result.put("assigned", assigned == null ? "" : assigned);
        result.put("skillset", node.requiredSkillset());
        result.put("dependencies", node.dependsOn().stream().sorted().toList());
        result.put("description", node.description());
        result.put(
                "artifact",
                node.result() instanceof DagNode.ResultArtifact value ? value.artifactPath() : "");
        result.put(
                "report",
                node.result() instanceof DagNode.ResultSuccess value ? value.summary() : "");
        result.put(
                "failure",
                node.result() instanceof DagNode.ResultFailure value ? value.feedback() : "");
        result.put(
                "logs",
                node.result() instanceof DagNode.ResultFailure value ? value.logRefs() : List.of());
        return result;
    }
}
