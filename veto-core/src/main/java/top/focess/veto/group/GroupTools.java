package top.focess.veto.group;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeMcpTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolCallContext;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolSecurity;

/**
 * The agent-facing group management tools (delegation_spawning.md). The Leader uses these to create
 * / disband groups, add / remove Mates, and dispatch tasks. Mates use {@code postMessage} to send
 * messages to the Leader (blackboard.md §3.2).
 *
 * <p>These are native tools — they pass through the Gateway (read of own data is SAFE, writes are
 * ELEVATED + audited). Each tool's {@code execute(...)} body is wired to the group runtime ({@link
 * GroupSpawner} / {@link Blackboard} / {@link GroupRegistry}); the {@code create_group} path spawns
 * a registered group + DAG that {@link GroupOrchestrator#tick} (driven by {@link
 * GroupTickScheduler}) advances once Mates are added via {@code create_mate}.
 *
 * <p><b>Tool call-context gap:</b> the {@code NativeMcpTool.execute(Args)} contract carries no
 * caller identity, so the tools use placeholder {@code leaderId}/{@code userId}/{@code senderId}
 * values until per-call context (the calling agent's id) is threaded through tool execution.
 */
public final class GroupTools {

    private GroupTools() {}

    /** {@code create_group} — spawn a delegation. The Leader calls this on the top-tier model. */
    @Component
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class CreateGroup implements NativeMcpTool<CreateGroup.Args> {

        private final GroupSpawner spawner;

        public CreateGroup(GroupSpawner spawner) {
            this.spawner = spawner;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "Short brief of the work to be done (seeds the Leader's investigation).")
                        String task,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Optional JSON-encoded DAG (will be parsed on dispatch).")
                        String dag) {}

        @Override
        public String getName() {
            return "create_group";
        }

        @Override
        public String getDescription() {
            return "Spawn a delegation (a group of agents) to accomplish a goal. "
                    + "The Leader authors the DAG from the contextBrief; Mates are created via create_mate.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(Args args) {
            String task = args.task() == null ? "" : args.task();
            UUID groupId = UUID.randomUUID();
            ExecutionDag dag =
                    (args.dag() != null && !args.dag().isBlank())
                            ? LlmLeader.parseDagFromJson(groupId, args.dag())
                            : null;
            if (dag == null) {
                // Single-node DAG seeded with the task; the Leader refines via create_mate +
                // dispatch.
                dag =
                        new ExecutionDag(
                                groupId,
                                List.of(
                                        new DagNode(
                                                "n1",
                                                task,
                                                null,
                                                "coding",
                                                Set.of(),
                                                DagNode.NodeState.PENDING,
                                                new DagNode.ResultNone(),
                                                0)));
            }
            // Resolve leaderId and userId from tool call context
            ToolCallContext ctx = ToolCallContextHolder.get();
            String leaderId = ctx != null ? ctx.agentId() : "leader";
            String userId = ctx != null ? ctx.userId().toString() : "default";
            Group g = spawner.spawnGroup(leaderId, userId, task, dag);
            return g.groupId().toString();
        }
    }

    /** {@code disband_group} — tear down an active group. */
    @Component
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class DisbandGroup implements NativeMcpTool<DisbandGroup.Args> {

        private final GroupSpawner spawner;

        public DisbandGroup(GroupSpawner spawner) {
            this.spawner = spawner;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("The group id to disband.")
                        String groupId) {}

        @Override
        public String getName() {
            return "disband_group";
        }

        @Override
        public String getDescription() {
            return "Tear down an active group. The Blackboard is retained for audit.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(Args args) {
            spawner.disband(UUID.fromString(args.groupId()));
            return "disbanded";
        }
    }

    /** {@code create_mate} — add a Mate to a group. */
    @Component
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class CreateMate implements NativeMcpTool<CreateMate.Args> {

        private final GroupSpawner spawner;
        private final GroupSpawner.AgentFactory agentFactory;

        public CreateMate(GroupSpawner spawner, GroupSpawner.AgentFactory agentFactory) {
            this.spawner = spawner;
            this.agentFactory = agentFactory;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Group id.") String groupId,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("New Mate's id (unique within the group).")
                        String mateId,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Skillset the Mate specializes in (e.g. 'coding', 'testing').")
                        String skillset) {}

        @Override
        public String getName() {
            return "create_mate";
        }

        @Override
        public String getDescription() {
            return "Add a Mate to an active group. The Mate joins under a skillset tag.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(Args args) {
            spawner.addMate(
                    UUID.fromString(args.groupId()), args.mateId(), args.skillset(), agentFactory);
            return "mate added";
        }
    }

    /** {@code remove_mate} — remove a Mate from a group. */
    @Component
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class RemoveMate implements NativeMcpTool<RemoveMate.Args> {

        private final GroupSpawner spawner;

        public RemoveMate(GroupSpawner spawner) {
            this.spawner = spawner;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Group id.") String groupId,
                @SecurityHint(ParamCategory.GENERIC) @Doc("Mate id to remove.") String mateId) {}

        @Override
        public String getName() {
            return "remove_mate";
        }

        @Override
        public String getDescription() {
            return "Remove a Mate from an active group. In-flight nodes go to PENDING for re-assignment.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(Args args) {
            spawner.removeMate(UUID.fromString(args.groupId()), args.mateId());
            return "mate removed";
        }
    }

    /** {@code dispatchTask} — Leader → Mate: a task or revision instruction. */
    @Component
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class DispatchTask implements NativeMcpTool<DispatchTask.Args> {

        private final Blackboard blackboard;

        public DispatchTask(Blackboard blackboard) {
            this.blackboard = blackboard;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Group id.") String groupId,
                @SecurityHint(ParamCategory.GENERIC) @Doc("Target Mate id.") String mateId,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "The Leader's authored instruction (the Leader reasons over any "
                                        + "incoming messages and composes this itself; no pass-through).")
                        String instruction) {}

        @Override
        public String getName() {
            return "dispatchTask";
        }

        @Override
        public String getDescription() {
            return "Push a Leader-authored task or revision instruction to a Mate. "
                    + "Posted to the Blackboard as TASK_DISPATCH.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(Args args) {
            // Payload shape "<nodeId>:<instruction>" is what MateAgent.handleDispatch parses; for
            // an
            // ad-hoc dispatch (not tied to a DAG node) the mateId stands in as the nodeId.
            String payload = args.mateId() + ":" + args.instruction();
            blackboard.post(
                    new BlackboardMessage(
                            UUID.randomUUID().toString(),
                            UUID.fromString(args.groupId()),
                            "LEADER",
                            args.mateId(),
                            BlackboardMessage.MessageType.TASK_DISPATCH,
                            payload,
                            0));
            return "dispatched";
        }
    }

    /** {@code postMessage} — Mate → Leader: ARTIFACT_REF / LOG_REF / FEEDBACK / STATUS / ACCEPT. */
    @Component
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class PostMessage implements NativeMcpTool<PostMessage.Args> {

        private final Blackboard blackboard;

        public PostMessage(Blackboard blackboard) {
            this.blackboard = blackboard;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Group id.") String groupId,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Message type: ARTIFACT_REF, LOG_REF, FEEDBACK, STATUS, ACCEPT.")
                        String type,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "Small payload (path / status / short feedback / node-task instruction). "
                                        + "Mates must NOT post file contents — paths only.")
                        String payload) {}

        @Override
        public String getName() {
            return "postMessage";
        }

        @Override
        public String getDescription() {
            return "Mate → Leader message (ARTIFACT_REF / LOG_REF / FEEDBACK / STATUS / ACCEPT). "
                    + "Receiver is always the Leader (hub-and-spoke).";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(Args args) {
            BlackboardMessage.MessageType type = BlackboardMessage.MessageType.valueOf(args.type());
            // Resolve senderId from tool call context (the calling Mate's agentId)
            ToolCallContext ctx = ToolCallContextHolder.get();
            String senderId = ctx != null ? ctx.agentId() : "mate";
            blackboard.post(
                    new BlackboardMessage(
                            UUID.randomUUID().toString(),
                            UUID.fromString(args.groupId()),
                            senderId,
                            "LEADER",
                            type,
                            args.payload(),
                            0));
            return "posted";
        }
    }

    /** {@code postStatus} — Leader/Mate: update a DAG node's status (PENDING/ASSIGNED/...). */
    @Component
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class PostStatus implements NativeMcpTool<PostStatus.Args> {

        private final GroupRegistry registry;

        public PostStatus(GroupRegistry registry) {
            this.registry = registry;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Group id.") String groupId,
                @SecurityHint(ParamCategory.GENERIC) @Doc("DAG node id.") String nodeId,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "New status: PENDING / ASSIGNED / RUNNING / VERIFIED / FAILED / STALE.")
                        String status) {}

        @Override
        public String getName() {
            return "postStatus";
        }

        @Override
        public String getDescription() {
            return "Update a DAG node's status. Persists in the group record.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(Args args) {
            Group g = registry.get(UUID.fromString(args.groupId()));
            if (g == null) {
                return "unknown group";
            }
            DagNode.NodeState state = DagNode.NodeState.valueOf(args.status());
            ExecutionDag dag = g.dag();
            for (DagNode n : dag.nodes()) {
                if (n.nodeId().equals(args.nodeId())) {
                    DagNode updated =
                            new DagNode(
                                    n.nodeId(),
                                    n.description(),
                                    n.assignedMateId(),
                                    n.requiredSkillset(),
                                    n.dependsOn(),
                                    state,
                                    n.result(),
                                    n.retryCount());
                    registry.put(g.withDag(dag.withNode(args.nodeId(), updated)));
                    return "ok";
                }
            }
            return "node not found";
        }
    }
}
