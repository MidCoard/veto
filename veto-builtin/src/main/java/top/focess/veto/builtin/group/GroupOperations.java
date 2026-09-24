package top.focess.veto.builtin.group;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.builtin.group.BlackboardMessage.MessageType;

final class GroupOperations implements GroupControlCapability, DelegationCapability {
    private final @NonNull Supplier<@NonNull GroupRuntime> runtime;
    private final @NonNull GroupSpawner spawner;
    private final @NonNull GroupRegistry registry;
    private final @NonNull Blackboard blackboard;
    private final @NonNull GroupOrchestrator orchestrator;

    GroupOperations(
            @NonNull Supplier<@NonNull GroupRuntime> runtime,
            @NonNull GroupSpawner spawner,
            @NonNull GroupRegistry registry,
            @NonNull Blackboard board,
            @NonNull GroupOrchestrator orchestrator) {
        this.runtime = runtime;
        this.spawner = spawner;
        this.registry = registry;
        this.blackboard = board;
        this.orchestrator = orchestrator;
    }

    private PluginHost.@NonNull Invocation invocation(@NonNull String tool) {
        return runtime.get().invocation(tool);
    }

    public void createGroup(@NonNull String task) {
        runtime.get().create(invocation("create_group"), task);
    }

    public @NonNull String prompt(@NonNull String source, @NonNull Map<String, Object> data) {
        return runtime.get().prompt(source, data);
    }

    private UUID groupId(PluginHost.@NonNull Invocation scope) {
        return registry.snapshot().values().stream()
                .filter(
                        group ->
                                group.leaderId().equals(scope.agentId())
                                        && scope.owner().equals(group.owner())
                                        && group.sessionId() != null
                                        && scope.sessionId()
                                                .equals(String.valueOf(group.sessionId()))
                                        && group.state() != GroupState.DISBANDED)
                .map(Group::groupId)
                .findFirst()
                .orElse(null);
    }

    private void requireLeader(PluginHost.@NonNull Invocation scope) {
        UUID id = groupId(scope);
        Group group = id == null ? null : registry.get(id);
        if (group != null && group.state() == GroupState.RECOVERING)
            throw new SecurityException("Team recovery is incomplete");
    }

    @Override
    public GroupSnapshot snapshot() {
        var ctx = invocation("inspect_group");
        requireLeader(ctx);
        UUID id = groupId(ctx);
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
    public Inspection inspect(long since) {
        var ctx = invocation("inspect_group");
        requireLeader(ctx);
        UUID id = groupId(ctx);
        return id == null ? null : orchestrator.inspect(id, since);
    }

    @Override
    public @NonNull List<@NonNull BlackboardMessage> messages(long since) {
        var ctx = invocation("inspect_group");
        requireLeader(ctx);
        UUID id = groupId(ctx);
        return id == null ? List.of() : newMessages(id, since);
    }

    @Override
    public void disband(@NonNull String brief) {
        var ctx = invocation("disband_group");
        requireLeader(ctx);
        UUID id = groupId(ctx);
        if (id == null) {
            ToolErrors.failure(
                    ToolErrorCode.GROUP.NO_ACTIVE_GROUP,
                    "Group not disbanded: no active group in your context. disband_group is a Leader tool inside a group.");
            return;
        }
        orchestrator.closeGroup(id, () -> spawner.disband(id));
        runtime.get().transition(ctx, "runtime-disband", brief);
    }

    @Override
    public void awaitChange(long since, @NonNull GroupState state, int waitSeconds)
            throws InterruptedException {
        var ctx = invocation("inspect_group");
        requireLeader(ctx);
        UUID id = groupId(ctx);
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
        var ctx = invocation("post_message");
        requireLeader(ctx);
        if (type == MessageType.TASK_DISPATCH || type == MessageType.ACCEPT) {
            throw new SecurityException(
                    "Task dispatch and completion belong to the DAG execution flow; use create_node for work.");
        }
        if (!"LEADER".equals(receiver)) {
            throw new SecurityException(
                    "Mate instructions must be tracked tasks; use create_node with mateId.");
        }
        UUID id = groupId(ctx);
        Group group = id == null ? null : registry.get(id);
        if (group == null || group.state() == GroupState.DISBANDED)
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
        var ctx = invocation("create_mate");
        requireLeader(ctx);
        UUID id = groupId(ctx);
        if (id == null)
            return ToolErrors.failure(
                    ToolErrorCode.GROUP.NO_ACTIVE_GROUP,
                    "Mate not created: no active group in your context. create_mate is a Leader tool inside a group.");
        return orchestrator.createMate(id, name, responsibility, spawner);
    }

    @Override
    public @NonNull NodeEdit createTask(
            @NonNull String id,
            @NonNull String description,
            @NonNull String mateId,
            @NonNull String responsibility,
            @NonNull Set<String> dependencies) {
        var ctx = invocation("create_task");
        requireLeader(ctx);
        UUID groupId = groupId(ctx);
        Group group = groupId == null ? null : registry.get(groupId);
        if (group == null)
            return ToolErrors.failure(
                    ToolErrorCode.GROUP.NO_ACTIVE_GROUP,
                    "Task not created: no active group in your context. create_task is a Leader tool inside a group.");
        return orchestrator.addNode(
                group.groupId(),
                id,
                description,
                responsibility,
                dependencies,
                mateId,
                false,
                ctx.requestId());
    }

    @Override
    public @NonNull NodeEdit addNode(
            @NonNull String id,
            @NonNull String description,
            @NonNull String skillset,
            @NonNull Set<String> dependencies,
            String mateId,
            boolean newMate) {
        var ctx = invocation("create_node");
        requireLeader(ctx);
        UUID groupId = groupId(ctx);
        if (groupId == null)
            return ToolErrors.failure(
                    ToolErrorCode.GROUP.NO_ACTIVE_GROUP,
                    "Node not created: no active group in your context. create_node is a Leader tool inside a group.");
        return orchestrator.addNode(
                groupId, id, description, skillset, dependencies, mateId, newMate, ctx.requestId());
    }

    @Override
    public @NonNull NodeEdit removeNode(@NonNull String id) {
        var ctx = invocation("remove_node");
        requireLeader(ctx);
        UUID groupId = groupId(ctx);
        if (groupId == null)
            return ToolErrors.failure(
                    ToolErrorCode.GROUP.NO_ACTIVE_GROUP,
                    "Node not removed: no active group in your context. remove_node is a Leader tool inside a group.");
        return orchestrator.removeNode(groupId, id);
    }

    @Override
    public @NonNull NodeEdit removeMate(@NonNull String id) {
        var ctx = invocation("remove_mate");
        requireLeader(ctx);
        UUID groupId = groupId(ctx);
        if (groupId == null)
            return ToolErrors.failure(
                    ToolErrorCode.GROUP.NO_ACTIVE_GROUP,
                    "Mate not removed: no active group in your context. remove_mate is a Leader tool inside a group.");
        return orchestrator.removeMate(groupId, id, spawner);
    }

    @Override
    public @NonNull NodeEdit cancelTask(@NonNull String id) {
        var ctx = invocation("cancel_group_task");
        requireLeader(ctx);
        UUID groupId = groupId(ctx);
        if (groupId == null)
            return ToolErrors.failure(
                    ToolErrorCode.GROUP.NO_ACTIVE_GROUP,
                    "Task not cancelled: no active group in your context. cancel_group_task is a Leader tool inside a group.");
        return orchestrator.cancelTask(groupId, id, spawner);
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
}
