package top.focess.veto.agent.capability;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.group.Blackboard;
import top.focess.veto.group.BlackboardMessage;
import top.focess.veto.group.DagNode;
import top.focess.veto.group.DagTools.CreateNode;
import top.focess.veto.group.DagTools.RemoveNode;
import top.focess.veto.group.Group;
import top.focess.veto.group.GroupOrchestrator;
import top.focess.veto.group.GroupOrchestrator.NodeEdit;
import top.focess.veto.group.GroupRegistry;
import top.focess.veto.group.GroupSpawner;
import top.focess.veto.group.GroupTools.DisbandGroup;
import top.focess.veto.group.GroupTools.InspectGroup;
import top.focess.veto.group.GroupTools.PostMessage;

@Component
public final class GroupControlCapabilityImpl implements GroupControlCapability {
    private static final int MAX_PAYLOAD_CHARS = 4096;
    private final @NonNull GroupSpawner spawner;
    private final @NonNull GroupRegistry registry;
    private final @NonNull Blackboard blackboard;
    private final @NonNull GroupOrchestrator orchestrator;

    public GroupControlCapabilityImpl(
            @NonNull GroupSpawner spawner,
            @NonNull GroupRegistry registry,
            @NonNull Blackboard blackboard,
            @NonNull GroupOrchestrator orchestrator) {
        this.spawner = spawner;
        this.registry = registry;
        this.blackboard = blackboard;
        this.orchestrator = orchestrator;
    }

    @Override
    public @NonNull String disband(DisbandGroup.@NonNull Args args) {
        ToolCallContext ctx =
                CapabilityAccess.require(ToolCapability.GROUP_CONTROL, "disband_group");
        requireLeader(ctx);

        UUID groupId = ctx.groupId();
        if (groupId == null) {
            return ToolErrors.failure(
                    "Group not disbanded: no active group in your context. disband_group is "
                            + "a Leader tool inside a group.");
        }
        // Summarize the group's outcome for the reverse-transform brief (verified nodes' \
        // results), then tear the group down.
        String brief = buildDisbandBrief(registry.get(groupId));
        spawner.disband(groupId);
        // Request the reverse transform: the runner rewinds, restores the STANDALONE persona +
        // binding, and re-injects the outcome brief so the agent continues autonomously.
        ToolCallContextHolder.requestReverseTransform(brief);
        return "";
    }

    @Override
    public @NonNull String inspect(InspectGroup.@NonNull Args args) {
        ToolCallContext ctx =
                CapabilityAccess.require(ToolCapability.GROUP_CONTROL, "inspect_group", args);
        requireLeader(ctx);

        UUID groupId = ctx.groupId();
        if (groupId == null) {
            return ToolErrors.failure(
                    "Group not inspected: no active group in your context. inspect_group is a "
                            + "Leader tool inside a group.");
        }
        Long requestedSince = args.sinceSeq();
        long since = requestedSince == null ? 0 : requestedSince;
        if (since < 0) {
            return ToolErrors.failure("Group not inspected: sinceSeq must be non-negative.");
        }
        Integer requestedWait = args.waitSeconds();
        int waitSeconds = requestedWait == null ? 0 : requestedWait;
        waitSeconds = Math.max(0, Math.min(30, waitSeconds));
        Group initial = registry.get(groupId);
        if (initial == null) {
            return ToolErrors.failure("Group not inspected: group record not found.");
        }
        Group group = initial;
        List<BlackboardMessage> messages = newMessages(groupId, since);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds);
        while (messages.isEmpty()
                && group.state() == initial.state()
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolErrors.failure("Group not inspected: wait interrupted.");
            }
            Group refreshed = registry.get(groupId);
            if (refreshed == null) {
                return ToolErrors.failure("Group not inspected: group record disappeared.");
            }
            group = refreshed;
            messages = newMessages(groupId, since);
        }
        return render(group, messages, since);
    }

    @Override
    public @NonNull String post(PostMessage.@NonNull Args args) {
        ToolCallContext ctx =
                CapabilityAccess.require(ToolCapability.GROUP_CONTROL, "post_message", args);
        requireLeader(ctx);

        UUID groupId = ctx.groupId();
        if (groupId == null) {
            return ToolErrors.failure(
                    "Not posted: no active group in your context. post_message is a Leader "
                            + "tool inside a group.");
        }
        // The Blackboard identifies the Leader by the literal "LEADER" (its hub-and-spoke guard
        // + the orchestrator's ingest both key on it), so the Leader posts as "LEADER".
        String requestedReceiver = args.receiver();
        String receiver = requestedReceiver == null ? "LEADER" : requestedReceiver;
        Group group = registry.get(groupId);
        if (group != null && !group.isActive()) {
            return ToolErrors.failure("Not posted: group is no longer active.");
        }
        if (group == null || (!"LEADER".equals(receiver) && !group.mates().containsKey(receiver))) {
            return ToolErrors.failure("Not posted: unknown receiver '" + receiver + "'.");
        }
        String payload = args.payload();
        if (payload.isBlank()) {
            return ToolErrors.failure("Not posted: payload must not be blank.");
        }
        if (payload.length() > MAX_PAYLOAD_CHARS) {
            return ToolErrors.failure("Not posted: payload exceeds 4096 characters.");
        }
        blackboard.post(
                new BlackboardMessage(
                        UUID.randomUUID().toString(),
                        groupId,
                        "LEADER",
                        receiver,
                        args.type(),
                        payload,
                        0));
        return "posted";
    }

    @Override
    public @NonNull String createNode(CreateNode.@NonNull Args args) {
        ToolCallContext ctx =
                CapabilityAccess.require(ToolCapability.GROUP_CONTROL, "create_node", args);
        requireLeader(ctx);
        UUID groupId = ctx.groupId();
        if (groupId == null) {
            return ToolErrors.failure(
                    "Node not created: no active group in your context. create_node is "
                            + "a Leader tool inside a group.");
        }
        String nodeId = args.nodeId().strip();
        String description = args.description().strip();
        String skillset = args.skillset().strip();
        var requestedDependencies = args.dependsOn();
        Set<String> deps =
                requestedDependencies == null
                        ? Set.of()
                        : new LinkedHashSet<>(requestedDependencies);
        NodeEdit edit = orchestrator.addNode(groupId, nodeId, description, skillset, deps);
        if (edit instanceof NodeEdit.Rejected r) {
            return ToolErrors.failure("Node not created: " + r.reason());
        }
        if (deps.isEmpty()) {
            return "Node created: "
                    + nodeId
                    + " (skillset: "
                    + skillset
                    + "). It is eligible for dispatch.";
        }
        return "Node created: "
                + nodeId
                + " (skillset: "
                + skillset
                + ", depends on: "
                + String.join(", ", deps)
                + "). It becomes eligible after its dependencies verify.";
    }

    @Override
    public @NonNull String removeNode(RemoveNode.@NonNull Args args) {
        ToolCallContext ctx =
                CapabilityAccess.require(ToolCapability.GROUP_CONTROL, "remove_node", args);
        requireLeader(ctx);
        UUID groupId = ctx.groupId();
        if (groupId == null) {
            return ToolErrors.failure(
                    "Node not removed: no active group in your context. remove_node is "
                            + "a Leader tool inside a group.");
        }
        String nodeId = args.nodeId().strip();
        NodeEdit edit = orchestrator.removeNode(groupId, nodeId);
        if (edit instanceof NodeEdit.Rejected r) {
            return ToolErrors.failure("Node not removed: " + r.reason());
        }
        return "Node removed: " + nodeId + " (marked stale).";
    }

    /** Builds the outcome brief seeded into the now-STANDALONE agent's context after disband. */
    private static @NonNull String buildDisbandBrief(Group g) {
        StringBuilder sb = new StringBuilder();
        sb.append("Delegation complete. You led a group to the following outcome.\n");
        if (g == null) {
            sb.append("(group record no longer available)\n");
            return sb.toString();
        }
        sb.append("Group id: ").append(g.groupId()).append('\n');
        sb.append("Original brief: ")
                .append(g.contextBrief().isBlank() ? "(unspecified)" : g.contextBrief())
                .append('\n');
        sb.append("Node outcomes:\n");
        for (DagNode n : g.dag().nodes()) {
            sb.append("  - ").append(n.nodeId()).append(" (").append(n.state());
            if (n.assignedMateId() != null) {
                sb.append(", mate: ").append(n.assignedMateId());
            }
            sb.append("): ").append(n.description()).append('\n');
            if (n.result() instanceof DagNode.ResultArtifact artifact) {
                sb.append("    Artifact: ").append(artifact.artifactPath()).append('\n');
            } else if (n.result() instanceof DagNode.ResultSuccess success) {
                sb.append("    Mate report: ").append(success.summary()).append('\n');
            } else if (n.result() instanceof DagNode.ResultFailure failure) {
                sb.append("    Failure: ").append(failure.feedback()).append('\n');
                if (!failure.logRefs().isEmpty()) {
                    sb.append("    Logs: ")
                            .append(String.join(", ", failure.logRefs()))
                            .append('\n');
                }
            }
        }
        sb.append("You are back in single-agent autonomous mode. Continue from here.");
        return sb.toString();
    }

    private @NonNull List<@NonNull BlackboardMessage> newMessages(
            @NonNull UUID groupId, long since) {
        Group group = registry.get(groupId);
        if (group == null) {
            return List.of();
        }
        return blackboard.readFor(groupId, "LEADER").stream()
                .filter(message -> message.turnSeq() > since)
                .filter(
                        message ->
                                group.mates().containsKey(message.senderId())
                                        || "LEADER".equals(message.senderId()))
                .toList();
    }

    private static @NonNull String render(
            @NonNull Group group, @NonNull List<@NonNull BlackboardMessage> messages, long since) {
        StringBuilder result = new StringBuilder();
        result.append("Group state: ").append(group.state()).append('\n');
        result.append("Nodes:\n");
        if (group.dag().nodes().isEmpty()) {
            result.append("- (none)\n");
        }
        for (DagNode node : group.dag().nodes()) {
            result.append("- ")
                    .append(node.nodeId())
                    .append(" [")
                    .append(node.state())
                    .append("] mate=")
                    .append(node.assignedMateId() == null ? "(unassigned)" : node.assignedMateId())
                    .append(" skillset=")
                    .append(node.requiredSkillset())
                    .append('\n');
            if (node.result() instanceof DagNode.ResultSuccess success) {
                result.append("  report: ").append(oneLine(success.summary())).append('\n');
            } else if (node.result() instanceof DagNode.ResultFailure failure) {
                result.append("  failure: ").append(oneLine(failure.feedback())).append('\n');
            }
        }
        result.append("New Mate messages:\n");
        if (messages.isEmpty()) {
            result.append("- (none)\n");
        }
        long next = since;
        for (BlackboardMessage message : messages) {
            next = Math.max(next, message.turnSeq());
            result.append("- seq=")
                    .append(message.turnSeq())
                    .append(" sender=")
                    .append(message.senderId())
                    .append(" type=")
                    .append(message.type())
                    .append(" payload=")
                    .append(oneLine(message.payload()))
                    .append('\n');
        }
        result.append("nextSinceSeq: ").append(next);
        return result.toString();
    }

    private static @NonNull String oneLine(@NonNull String value) {
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }

    private void requireLeader(@NonNull ToolCallContext ctx) {
        UUID groupId = ctx.groupId();
        if (groupId == null) return;
        Group group = registry.get(groupId);
        String owner = group == null ? null : group.owner();
        if (group == null
                || !group.leaderId().equals(ctx.agentId())
                || (owner != null && !owner.equals(ctx.owner()))) {
            throw new SecurityException("This operation is unavailable outside your own group.");
        }
    }
}
