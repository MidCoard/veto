package top.focess.veto.agent.capability;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.group.Blackboard;
import top.focess.veto.group.BlackboardMessage;
import top.focess.veto.group.BlackboardMessage.MessageType;
import top.focess.veto.group.Group;
import top.focess.veto.group.Group.GroupState;
import top.focess.veto.group.GroupOrchestrator;
import top.focess.veto.group.GroupOrchestrator.NodeEdit;
import top.focess.veto.group.GroupRegistry;
import top.focess.veto.group.GroupSnapshot;
import top.focess.veto.group.GroupSpawner;

@Component
public final class GroupControlCapabilityImpl implements GroupControlCapability {
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
    public GroupSnapshot snapshot() {
        var ctx = CapabilityAccess.require(ToolCapability.GROUP_CONTROL);
        requireLeader(ctx);
        UUID id = ctx.groupId();
        Group group = id == null ? null : registry.get(id);
        return group == null
                ? null
                : new GroupSnapshot(
                        group.groupId(),
                        group.contextBrief(),
                        List.copyOf(group.dag().nodes()),
                        group.mates(),
                        group.state());
    }

    @Override
    public GroupOrchestrator.Inspection inspect(long since) {
        var ctx = CapabilityAccess.require(ToolCapability.GROUP_CONTROL, "inspect_group");
        requireLeader(ctx);
        UUID id = ctx.groupId();
        return id == null ? null : orchestrator.inspect(id, since);
    }

    @Override
    public @NonNull List<@NonNull BlackboardMessage> messages(long since) {
        var ctx = CapabilityAccess.require(ToolCapability.GROUP_CONTROL, "inspect_group");
        requireLeader(ctx);
        UUID id = ctx.groupId();
        return id == null ? List.of() : newMessages(id, since);
    }

    @Override
    public void disband(@NonNull String brief) {
        var ctx = CapabilityAccess.require(ToolCapability.GROUP_CONTROL, "disband_group");
        requireLeader(ctx);
        UUID id = ctx.groupId();
        if (id == null) throw new SecurityException("No active group");
        spawner.disband(id);
        ToolCallContextHolder.requestReverseTransform(brief);
    }

    @Override
    public void awaitChange(long since, @NonNull GroupState state, int waitSeconds)
            throws InterruptedException {
        var ctx = CapabilityAccess.require(ToolCapability.GROUP_CONTROL, "inspect_group");
        requireLeader(ctx);
        UUID id = ctx.groupId();
        if (id == null) return;
        blackboard.awaitChange(
                () -> {
                    Group current = registry.get(id);
                    return current == null
                            || current.state() != state
                            || !newMessages(id, since).isEmpty();
                },
                TimeUnit.SECONDS.toNanos(Math.max(0, Math.min(30, waitSeconds))));
    }

    @Override
    public void post(@NonNull String receiver, @NonNull MessageType type, @NonNull String payload) {
        var ctx = CapabilityAccess.require(ToolCapability.GROUP_CONTROL, "post_message");
        requireLeader(ctx);
        if (type == MessageType.TASK_DISPATCH || type == MessageType.ACCEPT) {
            throw new SecurityException(
                    "Task dispatch and completion belong to the DAG execution flow; use create_node for work.");
        }
        if (!"LEADER".equals(receiver)) {
            throw new SecurityException(
                    "Mate instructions must be tracked tasks; use create_node with mateId.");
        }
        UUID id = ctx.groupId();
        Group group = id == null ? null : registry.get(id);
        if (group == null
                || group.state() == GroupState.DISBANDED
                || (!"LEADER".equals(receiver) && !group.mates().containsKey(receiver)))
            throw new SecurityException("Receiver is unavailable in your active group");
        blackboard.post(
                new BlackboardMessage(
                        UUID.randomUUID().toString(),
                        group.groupId(),
                        "LEADER",
                        receiver,
                        type,
                        payload,
                        0));
    }

    @Override
    public @NonNull String createMate(@NonNull String name, @NonNull String responsibility) {
        var ctx = CapabilityAccess.require(ToolCapability.GROUP_CONTROL, "create_mate");
        requireLeader(ctx);
        UUID id = ctx.groupId();
        if (id == null) throw new SecurityException("No active group");
        return orchestrator.createMate(id, name, responsibility, spawner);
    }

    @Override
    public @NonNull NodeEdit createTask(
            @NonNull String id,
            @NonNull String description,
            @NonNull String mateId,
            @NonNull Set<String> dependencies) {
        var ctx = CapabilityAccess.require(ToolCapability.GROUP_CONTROL, "create_task");
        requireLeader(ctx);
        UUID groupId = ctx.groupId();
        Group group = groupId == null ? null : registry.get(groupId);
        if (group == null) throw new SecurityException("No active group");
        String responsibility = group.mates().get(mateId);
        if (responsibility == null)
            return new NodeEdit.Rejected("Unknown Mate in this group: " + mateId);
        return orchestrator.addNode(
                group.groupId(), id, description, responsibility, dependencies, mateId, false);
    }

    @Override
    public @NonNull NodeEdit addNode(
            @NonNull String id,
            @NonNull String description,
            @NonNull String skillset,
            @NonNull Set<String> dependencies,
            String mateId,
            boolean newMate) {
        var ctx = CapabilityAccess.require(ToolCapability.GROUP_CONTROL, "create_node");
        requireLeader(ctx);
        UUID groupId = ctx.groupId();
        if (groupId == null) throw new SecurityException("No active group");
        return orchestrator.addNode(
                groupId, id, description, skillset, dependencies, mateId, newMate);
    }

    @Override
    public @NonNull NodeEdit removeNode(@NonNull String id) {
        var ctx = CapabilityAccess.require(ToolCapability.GROUP_CONTROL, "remove_node");
        requireLeader(ctx);
        UUID groupId = ctx.groupId();
        if (groupId == null) throw new SecurityException("No active group");
        return orchestrator.removeNode(groupId, id);
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
