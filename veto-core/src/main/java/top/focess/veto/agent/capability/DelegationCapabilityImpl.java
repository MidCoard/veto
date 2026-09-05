package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.identity.RoleToolFilter;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.group.Group;
import top.focess.veto.group.GroupSpawner;
import top.focess.veto.group.GroupTools.CreateGroup;
import top.focess.veto.group.LeaderBinding;

@Component
public final class DelegationCapabilityImpl implements DelegationCapability {

    private final @NonNull GroupSpawner spawner;
    private final @NonNull LeaderBinding leaderBinding;
    private final @NonNull RoleToolFilter roleToolFilter;
    private final @NonNull HitlRegistry hitlRegistry;

    public DelegationCapabilityImpl(
            @NonNull GroupSpawner spawner,
            @NonNull LeaderBinding leaderBinding,
            @NonNull RoleToolFilter roleToolFilter,
            @NonNull HitlRegistry hitlRegistry) {
        this.spawner = spawner;
        this.leaderBinding = leaderBinding;
        this.roleToolFilter = roleToolFilter;
        this.hitlRegistry = hitlRegistry;
    }

    @Override
    public @NonNull String createGroup(CreateGroup.@NonNull Args args) {
        ToolCallContext ctx =
                CapabilityAccess.require(ToolCapability.DELEGATION, "create_group", args);
        String task = args.task().strip();
        if (task.isBlank()) {
            return ToolErrors.failure(
                    "Group not created: blank brief. Pass a real description of the work.");
        }
        // Resolve the calling STANDALONE's identity (the group owner / future Leader).

        String owner = ctx.owner();
        if (owner == null || owner.isBlank()) {
            return ToolErrors.failure(
                    "Group not created: no authenticated session owner is available.");
        }
        String leaderId = ctx.agentId();
        String userId = ctx.userId().toString();

        // Register an empty group - no DAG yet, no Mates. The Leader (the transformed caller)
        // authors the DAG node by node via create_node; the engine provisions Mates lazily on
        // dispatch.
        Group g =
                spawner.registerEmptyGroup(
                        leaderId,
                        userId,
                        owner,
                        task,
                        hitlRegistry.workspace(leaderId),
                        ctx.toolResultPresentation(),
                        ctx.guidedEnabled());

        // Request the delegation transform: the runner rewinds, re-seeds the Leader persona +
        // tool set + top-tier binding, stamps the group, and re-injects the brief. This call's
        // result string is discarded with the rewind; only a failure keeps the caller in the
        // single-agent loop with the reason.
        ToolCallContextHolder.requestTransform(
                new ToolCallContextHolder.TransformDirective(
                        task,
                        g.groupId(),
                        leaderBinding.binding(owner),
                        roleToolFilter.resolve(Role.LEADER)));
        return "";
    }
}
