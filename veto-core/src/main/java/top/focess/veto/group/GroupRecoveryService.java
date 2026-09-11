package top.focess.veto.group;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.identity.RoleToolFilter;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.session.SessionHistoryLoader;
import top.focess.veto.util.Nullness;

/**
 * Restores team identity on authenticated session activation without replaying interrupted work.
 */
@Service
public class GroupRecoveryService {
    private final @NonNull GroupHistoryStore history;
    private final @NonNull GroupRegistry groups;
    private final @NonNull Blackboard board;
    private final @NonNull GroupSpawner spawner;
    private final @NonNull SessionHistoryLoader turns;
    private final @NonNull SessionAgentRegistry agents;
    private final @NonNull LeaderBinding leaders;
    private final @NonNull RoleToolFilter tools;

    public GroupRecoveryService(
            @NonNull GroupHistoryStore history,
            @NonNull GroupRegistry groups,
            @NonNull Blackboard board,
            @NonNull GroupSpawner spawner,
            @NonNull SessionHistoryLoader turns,
            @NonNull SessionAgentRegistry agents,
            @NonNull LeaderBinding leaders,
            @NonNull RoleToolFilter tools) {
        this.history = history;
        this.groups = groups;
        this.board = board;
        this.spawner = spawner;
        this.turns = turns;
        this.agents = agents;
        this.leaders = leaders;
        this.tools = tools;
    }

    public synchronized void restore(
            @NonNull VetoAgent leader,
            @NonNull UUID session,
            @NonNull UUID user,
            @NonNull String owner,
            @NonNull Workspace workspace,
            @NonNull ToolResultPresentationMode presentation,
            boolean guided) {
        GroupHistoryView saved =
                history.latestSnapshots(session.toString()).stream()
                        .filter(view -> view.leaderId().equals(leader.id()))
                        .max(Comparator.comparing(GroupHistoryView::createdAt))
                        .orElse(null);
        if (saved == null || saved.state().equals("DISBANDED")) return;
        UUID id = UUID.fromString(saved.id());
        Group group = groups.get(id);
        var binding = leaders.binding(owner);
        if (group == null) {
            var mates = saved.mates();
            if (mates == null)
                throw new IllegalStateException(
                        "Team snapshot has no complete member roster; recovery is unavailable. History is retained.");
            List<DagNode> nodes =
                    saved.nodes().stream().map(GroupRecoveryService::restoreNode).toList();
            group =
                    new Group(
                            id,
                            leader.id(),
                            user.toString(),
                            saved.brief(),
                            new ExecutionDag(id, nodes),
                            board,
                            mates,
                            Group.GroupState.RECOVERING,
                            saved.createdAt(),
                            null,
                            owner,
                            workspace,
                            presentation,
                            guided,
                            session);
            groups.put(group);
        }
        if (!session.equals(group.sessionId())
                || !owner.equals(group.owner())
                || !leader.id().equals(group.leaderId()))
            throw new SecurityException("Team recovery scope mismatch");
        if (group.state() == Group.GroupState.RECOVERING) {
            var identities = new LinkedHashMap<String, SessionAgentRegistry.AgentSummary>();
            for (var identity : agents.records(session)) identities.put(identity.id(), identity);
            spawner.restoreMates(group, turns, identities);
            leader.restoreLeader(id, binding, tools.resolve(Role.LEADER));
            groups.put(group.withState(Group.GroupState.ACTIVE, Instant.now()));
        } else {
            leader.restoreLeader(id, binding, tools.resolve(Role.LEADER));
        }
    }

    public boolean refreshLeaderBinding(@NonNull VetoAgent leader) {
        if (leader.persona().role() != Role.LEADER) return false;
        for (Group group : groups.snapshot().values()) {
            String owner = group.owner();
            if (owner != null
                    && group.leaderId().equals(leader.id())
                    && leader.sessionId().equals(group.sessionId())
                    && group.state() != Group.GroupState.DISBANDED) {
                leader.restoreLeader(
                        group.groupId(), leaders.binding(owner), tools.resolve(Role.LEADER));
                return true;
            }
        }
        return false;
    }

    static @NonNull DagNode restoreNode(GroupHistoryView.@NonNull Node saved) {
        boolean interrupted =
                Set.of("PENDING", "RUNNING", "CANCEL_REQUESTED", "INTERRUPTED")
                        .contains(saved.state());
        DagNode.NodeState state =
                interrupted
                        ? DagNode.NodeState.INTERRUPTED
                        : saved.state().equals("COMPLETED")
                                ? DagNode.NodeState.VERIFIED
                                : Nullness.requireNonNull(DagNode.NodeState.valueOf(saved.state()));
        DagNode.NodeResult result =
                state == DagNode.NodeState.VERIFIED
                        ? new DagNode.ResultSuccess(saved.report())
                        : new DagNode.ResultFailure(
                                interrupted
                                        ? "Interrupted by runtime loss; not replayed. Inspect prior side effects before assigning new work. "
                                                + saved.report()
                                        : saved.report(),
                                List.of());
        return new DagNode(
                saved.id(),
                saved.description(),
                saved.mateId(),
                saved.skillset(),
                Set.copyOf(saved.dependencies()),
                state,
                result,
                saved.retries(),
                saved.dispatchId(),
                saved.requestId());
    }
}
