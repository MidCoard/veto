package top.focess.veto.group;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.mcp.AgentTool;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolCallContext;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolDoc;

/**
 * The agent-facing group management tools (delegation_spawning.md). The Leader uses these to create
 * / disband groups, add / remove Mates, and dispatch tasks. Mates use {@code postMessage} to send
 * messages to the Leader (blackboard.md §3.2).
 *
 * <p>These are agent tools — they carry {@link top.focess.veto.agent.mcp.RiskCategory#AGENT}; the
 * Gateway returns {@code NotScreened}. Each tool's {@code execute(...)} body is wired to the group
 * runtime ({@link GroupSpawner} / {@link Blackboard} / {@link GroupRegistry}); the {@code
 * create_group} path spawns a registered group + DAG that {@link GroupOrchestrator#tick} (driven by
 * {@link GroupTickScheduler}) advances once Mates are added via {@code create_mate}.
 *
 * <p><b>Tool call-context gap:</b> the {@code AgentTool.execute(Args)} contract carries no caller
 * identity, so the tools use placeholder {@code leaderId}/{@code userId}/{@code senderId} values
 * until per-call context (the calling agent's id) is threaded through tool execution.
 */
public final class GroupTools {

    private GroupTools() {}

    /** {@code create_group} — spawn a delegation. The Leader calls this on the top-tier model. */
    @Component
    @ToolDoc(
            description = "Spawn a delegation group. The calling agent transforms into the Leader.",
            usage =
                    """
                    #### When to use
                    Use `create_group` when the task is large enough to decompose into parallel \
                    sub-tasks that can be assigned to specialized Mate agents. The calling agent \
                    becomes the Leader and authors an Execution DAG.

                    #### When NOT to use
                    - Do not use `create_group` for tasks you can complete alone - stay autonomous.
                    - Do not use it when only one step is needed - a single agent is faster.
                    - Only STANDALONE and LEADER can call this; MATE cannot.

                    #### Behavior
                    The calling agent transforms into the Leader (context rewound to 0, AGENT_INIT \
                    with Leader system prompt, promoted to top-tier model). The `task` brief seeds \
                    the Leader's investigation. The Leader then authors the Execution DAG and spawns \
                    Mates via `create_mate`. Resource gate is checked before spawning (max concurrent \
                    Mates, per-agent breaker budget).

                    #### Return format
                    `{"status": "ok", "groupId": "group-abc123"}`

                    #### Errors & edge cases
                    If resource limits are exceeded, user confirmation is required. Only the calling \
                    agent can become Leader - you cannot create a group on behalf of another agent.

                    #### Security
                    Agent tool (`RiskCategory.AGENT`). The Gateway does not screen it. Bounded by \
                    resource gate at spawn time. Safe to call when role permits.
                    """,
            examples = {
                "{\"task\": \"Fix the authentication bug in UserService\"}",
                "{\"task\": \"Refactor the data layer\", \"dag\": \"{\\\"nodes\\\": [...]}\"}"
            })
    public static final class CreateGroup implements AgentTool<CreateGroup.Args> {

        private final GroupSpawner spawner;
        private final GroupSpawner.AgentFactory agentFactory;

        public CreateGroup(GroupSpawner spawner, GroupSpawner.AgentFactory agentFactory) {
            this.spawner = spawner;
            this.agentFactory = agentFactory;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "Short brief of the work to be done (seeds the Leader's investigation).")
                        String task,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "Optional JSON-encoded DAG (advice to the Leader; it refines/adopts it).")
                        String dag) {}

        @Override
        public String getName() {
            return "create_group";
        }

        @Override
        public String getDescription() {
            return "Spawn a delegation group. The calling agent transforms into the Leader.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(@NonNull Args args) {
            String task = args.task() == null ? "" : args.task();
            UUID groupId = UUID.randomUUID();

            // Resolve the calling STANDALONE's identity (the group owner).
            ToolCallContext ctx = ToolCallContextHolder.get();
            String leaderId = ctx != null ? ctx.agentId() : "leader";
            String userId = ctx != null ? ctx.userId().toString() : "default";

            // Spawn a one-shot Leader-role agent to analyze the brief (+ optional caller DAG) and
            // author the execution DAG. The Leader investigates via view_file/grep_search (its
            // LEADER-scoped tools) and returns a JSON DAG; authorDag blocks this thread (a virtual
            // thread, so cheap) up to 30s, falling back to a linear DAG on failure/timeout. The
            // caller-supplied `dag` is passed as advice in the brief for the Leader to refine.
            String brief = args.dag() != null && !args.dag().isBlank() ? args.dag().strip() : null;
            String contextBrief =
                    brief == null
                            ? task
                            : task + "\n\nCaller-provided DAG (refine/adopt):\n" + brief;
            AgentPersona leaderPersona =
                    new AgentPersona(
                            "leader-" + groupId,
                            "Group Leader",
                            "A Leader agent that decomposes a task into an execution DAG.",
                            Set.of(), // tools re-scoped to LEADER by createMate
                            List.of(),
                            null,
                            null,
                            null,
                            Role.LEADER);
            Agent leaderAgent = agentFactory.create(leaderPersona);
            ExecutionDag dag;
            try {
                dag = new LlmLeader(leaderPersona, leaderAgent).authorDag(groupId, contextBrief);
            } finally {
                // The Leader's only job was to author the DAG; the orchestrator drives the rest.
                leaderAgent.terminate();
            }

            // Register the group + authored DAG, then spawn one Mate per distinct skillset so the
            // orchestrator can assign pending nodes to a Mate of matching skillset.
            Group g = spawner.spawnGroup(leaderId, userId, task, dag);
            java.util.LinkedHashSet<String> skillsets = new java.util.LinkedHashSet<>();
            for (DagNode n : dag.nodes()) {
                if (n.requiredSkillset() != null && !n.requiredSkillset().isBlank()) {
                    skillsets.add(n.requiredSkillset());
                }
            }
            int mateIndex = 1;
            for (String skillset : skillsets) {
                spawner.addMate(g.groupId(), skillset + "-" + mateIndex++, skillset, agentFactory);
            }

            // Replace the caller's prior turns with a recall brief of the authored plan. Keeps the
            // seed turn (from_index=1, matching the compaction precedent) + re-injects the brief.
            ToolCallContextHolder.requestRecall(1, buildDelegationBrief(task, dag, g.groupId()));
            return "delegated";
        }

        /**
         * Builds the recall brief seeded into the delegating agent's context after create_group.
         */
        private static String buildDelegationBrief(
                @NonNull String task, @NonNull ExecutionDag dag, @NonNull UUID groupId) {
            StringBuilder sb = new StringBuilder();
            sb.append(
                            "Delegation complete. A Leader agent analyzed the task and arranged the work ")
                    .append(
                            "into an execution DAG; the orchestrator now drives it autonomously.\n");
            sb.append("Original task: ")
                    .append(task.isBlank() ? "(unspecified)" : task)
                    .append('\n');
            sb.append("Group id: ").append(groupId).append('\n');
            sb.append("Authored DAG:\n");
            for (DagNode n : dag.nodes()) {
                sb.append("  - ").append(n.nodeId()).append(" (").append(n.requiredSkillset());
                if (!n.dependsOn().isEmpty()) {
                    sb.append(", depends on ").append(n.dependsOn());
                }
                sb.append("): ").append(n.description()).append('\n');
            }
            sb.append(
                            "One Mate per skillset has been spawned; nodes dispatch as their dependencies ")
                    .append("verify. Continue with other work - the group runs on its own.");
            return sb.toString();
        }
    }

    /** {@code disband_group} — tear down an active group. */
    @Component
    @ToolDoc(
            description =
                    "Tear down an active group and return the agent to single-agent autonomous mode.",
            usage =
                    """
                    #### When to use
                    Use `disband_group` when the delegated work is complete or the user explicitly requests it. \
                    Returns the Leader to autonomous mode.

                    #### When NOT to use
                    - Do not disband while Mates are still running - wait for their status.
                    - Do not disband without user request unless all DAG nodes are VERIFIED.
                    - Only Leader can call this.

                    #### Behavior
                    Rewinds context to 0 + AGENT_INIT with autonomous persona. Blackboard is \
                    persisted for audit. All Mates are deprovisioned. DAG is marked as closed and \
                    persisted.

                    #### Return format
                    `{"status": "ok", "groupId": "group-abc123"}`

                    #### Errors & edge cases
                    Group not found -> error. Not Leader -> error. Mates still RUNNING -> warn \
                    (may lose in-flight work).

                    #### Security
                    Agent tool (`RiskCategory.AGENT`). The Gateway does not screen it. Safe to call \
                    when role permits.
                    """,
            examples = {"{\"groupId\": \"group-abc123\"}"})
    public static final class DisbandGroup implements AgentTool<DisbandGroup.Args> {

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
            return "Tear down an active group and return the agent to single-agent autonomous mode.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(@NonNull Args args) {
            spawner.disband(UUID.fromString(args.groupId()));
            return "disbanded";
        }
    }

    /** {@code create_mate} — add a Mate to a group. */
    @Component
    @ToolDoc(
            description = "Add a Mate (worker agent) to an active group.",
            usage =
                    """
                    #### When to use
                    Use `create_mate` as the Leader when authoring the Execution DAG - each Mate is \
                    assigned to DAG nodes requiring its skillset. Only Leader can call this.

                    #### When NOT to use
                    - Do not call from MATE or STANDALONE role.
                    - Do not exceed max concurrent Mates.
                    - Do not create a Mate with a duplicate `mateId` within the group.

                    #### Behavior
                    Provisions a new Mate agent with a specialized persona based on `skillset`. \
                    Assigned to DAG nodes requiring that skillset. Resource count is checked against \
                    max concurrent Mates limit.

                    #### Return format
                    `{"status": "ok", "groupId": "group-abc123", "mateId": "mate-tester"}`

                    #### Errors & edge cases
                    Duplicate `mateId` -> error. Max Mate count reached -> tool refused. Unknown \
                    `groupId` -> error.

                    #### Security
                    Agent tool (`RiskCategory.AGENT`). The Gateway does not screen it. Bounded by \
                    resource gate. Safe to call when role permits.
                    """,
            examples = {
                "{\"groupId\": \"group-abc123\", \"mateId\": \"mate-tester\", \"skillset\": \"testing\"}"
            })
    public static final class CreateMate implements AgentTool<CreateMate.Args> {

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
            return "Add a Mate (worker agent) to an active group.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(@NonNull Args args) {
            spawner.addMate(
                    UUID.fromString(args.groupId()), args.mateId(), args.skillset(), agentFactory);
            return "mate added";
        }
    }

    /** {@code remove_mate} — remove a Mate from a group. */
    @Component
    @ToolDoc(
            description =
                    "Remove a Mate from an active group. In-flight nodes go back to PENDING "
                            + "for re-assignment.",
            usage =
                    """
                    #### When to use
                    Use `remove_mate` when re-planning - a Mate is underperforming, a strategic \
                    pivot is needed, or the Mate's skillset is no longer required. Only Leader can \
                    call this.

                    #### When NOT to use
                    - Do not remove a Mate while it is making progress unless re-planning.
                    - Do not remove the last Mate if work remains.
                    - Not callable from MATE or STANDALONE.

                    #### Behavior
                    Deprovisions the Mate. In-flight DAG nodes revert to PENDING (not FAILED) for \
                    re-assignment to other Mates. Resources are freed.

                    #### Return format
                    `{"status": "ok", "groupId": "group-abc123", "mateId": "mate-coder-1"}`

                    #### Errors & edge cases
                    Mate not found in group -> error. Unknown `groupId` -> error.

                    #### Security
                    Agent tool (`RiskCategory.AGENT`). The Gateway does not screen it. Safe to call \
                    when role permits.
                    """,
            examples = {"{\"groupId\": \"group-abc123\", \"mateId\": \"mate-coder-1\"}"})
    public static final class RemoveMate implements AgentTool<RemoveMate.Args> {

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
            return "Remove a Mate from an active group. In-flight nodes go back to PENDING "
                    + "for re-assignment.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(@NonNull Args args) {
            spawner.removeMate(UUID.fromString(args.groupId()), args.mateId());
            return "mate removed";
        }
    }

    /** {@code dispatchTask} — Leader -> Mate: a task or revision instruction. */
    @Component
    @ToolDoc(
            description = "Push a task or revision instruction from Leader to Mate.",
            usage =
                    """
                    #### When to use
                    Use `dispatchTask` when the Leader has reasoned over Mate feedback and authored \
                    a new instruction for the next step. Only Leader can call this.

                    #### When NOT to use
                    - Do not echo Mate messages back - Leader decides, doesn't pass-through.
                    - Do not dispatch to a Mate that is not in the group.
                    - Do not dispatch without reasoning over prior feedback first.

                    #### Behavior
                    Posts the Leader's authored instruction to the Blackboard as TASK_DISPATCH \
                    message type. The Mate receives the instruction in its next turn. Leader \
                    reasons over messages, then writes its own dispatch (not echoing Mate's message).

                    #### Return format
                    `{"status": "ok", "groupId": "group-abc123", "mateId": "mate-coder"}`

                    #### Errors & edge cases
                    Mate not found in group -> error. Unknown `groupId` -> error.

                    #### Security
                    Agent tool (`RiskCategory.AGENT`). The Gateway does not screen it. \
                    Leader-authored content (no pass-through). Safe to call when role permits.
                    """,
            examples = {
                "{\"groupId\": \"group-abc123\", \"mateId\": \"mate-coder\", \"instruction\": \"Implement the UserService.login() method with JWT validation\"}"
            })
    public static final class DispatchTask implements AgentTool<DispatchTask.Args> {

        private final Blackboard blackboard;

        public DispatchTask(Blackboard blackboard) {
            this.blackboard = blackboard;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC) @Doc("Group id.") String groupId,
                @SecurityHint(ParamCategory.GENERIC) @Doc("Target Mate id.") String mateId,
                @SecurityHint(ParamCategory.GENERIC) @Doc("The Leader's authored instruction.")
                        String instruction) {}

        @Override
        public String getName() {
            return "dispatchTask";
        }

        @Override
        public String getDescription() {
            return "Push a task or revision instruction from Leader to Mate.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(@NonNull Args args) {
            // Payload shape "<nodeId>:<instruction>" is what MateAgent.handleDispatch parses; for
            // an ad-hoc dispatch (not tied to a DAG node) the mateId stands in as the nodeId.
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

    /**
     * {@code postMessage} — Mate -> Leader: ARTIFACT_REF / LOG_REF / FEEDBACK / STATUS / ACCEPT.
     */
    @Component
    @ToolDoc(
            description = "Post a message from Mate to Leader via the Blackboard.",
            usage =
                    """
                    #### When to use
                    Use `postMessage` when a Mate needs to communicate results, errors, or status to \
                    the Leader - posting an artifact reference, a log reference, feedback, or an \
                    acceptance. Only Mate can call this.

                    #### When NOT to use
                    - Do not post full file contents - only paths.
                    - Do not post from Leader role - Leaders dispatch, Mates report.
                    - Do not use for Mate-to-Mate communication - hub-and-spoke only (Mate knows \
                    only Leader).

                    #### Behavior
                    Posts a message to the Blackboard with the given type and payload. Message types: \
                    ARTIFACT_REF (completed work, path only), LOG_REF (error logs, path only), \
                    FEEDBACK (task feedback, short), STATUS (status update), ACCEPT (node verified). \
                    Payloads must be small - no file contents, paths only for artifacts/logs.

                    #### Return format
                    `{"status": "ok", "groupId": "group-abc123", "type": "ARTIFACT_REF"}`

                    #### Errors & edge cases
                    Unknown `groupId` -> error. Not Mate role -> error. Oversized payload -> warn \
                    (context saturation risk).

                    #### Security
                    Agent tool (`RiskCategory.AGENT`). The Gateway does not screen it. Hub-and-spoke: \
                    Mate knows only Leader. Safe to call when role permits.
                    """,
            examples = {
                "{\"groupId\": \"group-abc123\", \"type\": \"ARTIFACT_REF\", \"payload\": \"/output/UserServiceImpl.java\"}",
                "{\"groupId\": \"group-abc123\", \"type\": \"FEEDBACK\", \"payload\": \"node-5: Tests failed. See log.\"}"
            })
    public static final class PostMessage implements AgentTool<PostMessage.Args> {

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
                                "Small payload (path / status / short feedback). Mates must NOT post file contents - paths only.")
                        String payload) {}

        @Override
        public String getName() {
            return "postMessage";
        }

        @Override
        public String getDescription() {
            return "Post a message from Mate to Leader via the Blackboard.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(@NonNull Args args) {
            BlackboardMessage.MessageType type = BlackboardMessage.MessageType.valueOf(args.type());
            // Resolve senderId from tool call context (the calling Mate's agentId).
            // get() is @Nullable — a single null-check suffices.
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
    @ToolDoc(
            description = "Update a DAG node's execution status.",
            usage =
                    """
                    #### When to use
                    Use `postStatus` to advance a DAG node through its lifecycle - ASSIGNING to a \
                    Mate, marking RUNNING, reporting VERIFIED or FAILED. Both Leader and Mate can \
                    call this.

                    #### When NOT to use
                    - Do not set a status that violates the state machine (e.g. PENDING -> VERIFIED \
                    without RUNNING).
                    - Do not post status for a node that does not exist in the DAG.

                    #### Behavior
                    Updates the DAG node identified by `nodeId` to `status`. State machine: \
                    PENDING -> ASSIGNED -> RUNNING -> VERIFIED; any stage can transition to FAILED; \
                    FAILED triggers re-plan; STALE invalidates the node (Strategic Pivot). Persisted \
                    in group record for audit.

                    #### Return format
                    `{"status": "ok", "groupId": "group-abc123", "nodeId": "node-5", \
                    "nodeStatus": "VERIFIED"}`

                    #### Errors & edge cases
                    Unknown `groupId` or `nodeId` -> error. Invalid status transition -> error. \
                    Invalid status value -> error.

                    #### Security
                    Agent tool (`RiskCategory.AGENT`). The Gateway does not screen it. Persisted \
                    for audit. Safe to call when role permits.
                    """,
            examples = {
                "{\"groupId\": \"group-abc123\", \"nodeId\": \"node-5\", \"status\": \"VERIFIED\"}",
                "{\"groupId\": \"group-abc123\", \"nodeId\": \"node-5\", \"status\": \"FAILED\"}"
            })
    public static final class PostStatus implements AgentTool<PostStatus.Args> {

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
            return "Update a DAG node's execution status.";
        }

        @Override
        public Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public String execute(@NonNull Args args) {
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
