package top.focess.veto.group;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.Agent;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeMcpTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolCallContext;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolDoc;
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
        private final GroupSpawner.AgentFactory agentFactory;

        public CreateGroup(GroupSpawner spawner, GroupSpawner.AgentFactory agentFactory) {
            this.spawner = spawner;
            this.agentFactory = agentFactory;
        }

        @ToolDoc(
                description =
                        """
                        #### When to use
                        Use `create_group` to spawn a delegation - a group of agents (a Leader + Mates) to \
                        accomplish a goal that decomposes into parallel or specialized sub-tasks. The Leader \
                        authors the DAG and dispatches nodes to Mates. Call this as a STANDALONE agent when a \
                        task is large enough to benefit from decomposition.

                        #### When NOT to use
                        - Do not call `create_group` if you are already a Leader or a Mate - only the top-tier \
                        standalone agent spawns groups (Mates do not delegate further).
                        - Do not spawn a group for a single, self-contained task - just do it directly with the \
                        file/command tools.
                        - Do not call it without a clear `task` brief; the Leader investigates from that brief.

                        #### Behavior
                        Spawns a one-shot Leader-role agent that analyzes `task` (and `dag` if given) \
                        and authors the execution DAG - it investigates the codebase via `view_file` / \
                        `grep_search`, decomposes the work, and arranges dependencies. The group is then \
                        registered with the authored DAG and one Mate per distinct skillset is spawned so \
                        the orchestrator can assign + dispatch nodes. Nothing is returned to the caller - \
                        the delegation runs autonomously and a RECALL brief replaces the caller's prior \
                        turns with the authored plan. The leaderId and userId are resolved from the tool \
                        call context.

                        #### Return format
                        The string `delegated`. No group id is returned - the caller does not drive the \
                        group; the orchestrator does.

                        #### Errors & edge cases
                        - `dag` present but malformed -> ignored; the Leader authors the DAG from `task` alone.
                        - `task` empty/blank -> the group is created but the Leader has no brief; provide a \
                        concrete task.
                        - If the Leader agent cannot reach its model (no `veto.group.mate.*` configured) or \
                        times out (30s), authoring falls back to a single-node DAG seeded with `task` - the \
                        group still spawns and runs.
                        - One Mate per distinct DAG skillset is spawned automatically; the orchestrator ticks \
                        once Mates exist.

                        #### Security
                        `create_group` is `RiskCategory.FILE_WRITE` (elevated + audited) - it mutates group \
                        runtime state. Its parameters are GENERIC. The Leader/Mates it spawns inherit the \
                        deployer policy and gateway screening. Use for legitimate decomposition only.
                        """,
                examples = {
                    "{\"task\": \"refactor the auth module\"}",
                    "{\"task\": \"add integration tests for the vault layer\"}",
                    "{\"task\": \"migrate config to the new schema\"}",
                    "{\"task\": \"fix the flaky test suite\"}",
                    "{\"task\": \"implement and document the new tool\"}",
                    "{\"task\": \"audit all file-write tools for path screening\"}"
                })
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
            return "Spawn a delegation: a one-shot Leader agent analyzes the task and authors the DAG, "
                    + "Mates are spawned per skillset, and the orchestrator drives execution. "
                    + "Returns 'delegated' (no group id).";
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
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class DisbandGroup implements NativeMcpTool<DisbandGroup.Args> {

        private final GroupSpawner spawner;

        public DisbandGroup(GroupSpawner spawner) {
            this.spawner = spawner;
        }

        @ToolDoc(
                description =
                        """
                        #### When to use
                        Use `disband_group` to tear down an active delegation group once its goal is achieved, \
                        abandoned, or no longer needed. Frees the group's runtime state. The Blackboard is \
                        retained for audit.

                        #### When NOT to use
                        - Do not disband a group that still has in-flight work; let Mates finish or remove \
                        individual Mates via `remove_mate` first.
                        - Do not disband a group whose results you have not yet collected from the Blackboard.
                        - Do not call it with an unverified `groupId`; confirm the group exists.

                        #### Behavior
                        Disbands the group identified by `groupId` via the spawner. Returns "disbanded". The \
                        Blackboard (messages) is retained for audit.

                        #### Return format
                        The string `disbanded`.

                        #### Errors & edge cases
                        - `groupId` does not exist / not a valid UUID -> an exception is raised (UUID parsing \
                        fails).
                        - Disbanding a group with running Mates does not gracefully drain them; remove Mates \
                        first if order matters.
                        - The Blackboard survives disband for audit.

                        #### Security
                        `disband_group` is `RiskCategory.FILE_WRITE` (elevated + audited). Its parameter is \
                        GENERIC. It mutates group runtime state. Confirm the `groupId` before calling.
                        """,
                examples = {
                    "{\"groupId\": \"grp-1\"}",
                    "{\"groupId\": \"550e8400-e29b-41d4-a716-446655440000\"}",
                    "{\"groupId\": \"grp-7\"}",
                    "{\"groupId\": \"grp-leader-2\"}",
                    "{\"groupId\": \"grp-migration\"}"
                })
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
        public String execute(@NonNull Args args) {
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

        @ToolDoc(
                description =
                        """
                        #### When to use
                        Use `create_mate` to add a Mate (worker) to an active group, under a skillset tag (e.g. \
                        "coding", "testing"). The Leader calls this after `create_group` to populate the group \
                        with workers that will execute DAG nodes.

                        #### When NOT to use
                        - Do not call `create_mate` as a Mate (only the Leader adds Mates).
                        - Do not add Mates before `create_group` has returned a `groupId`.
                        - Do not reuse a `mateId` that already exists in the group.
                        - Do not add Mates without a plan to dispatch work to them.

                        #### Behavior
                        Adds a Mate with id `mateId` and skillset `skillset` to the group `groupId` via the \
                        spawner's AgentFactory. Returns "mate added". The Mate joins the Blackboard and becomes \
                        eligible for `dispatchTask`.

                        #### Return format
                        The string `mate added`.

                        #### Errors & edge cases
                        - `groupId` not found / invalid UUID -> exception.
                        - `mateId` already exists in the group -> spawner-dependent (likely rejected or \
                        overwritten); use unique ids.
                        - `skillset` should match a recognized specialization; an unrecognized skillset still \
                        creates the Mate but it may not be selected for typed nodes.

                        #### Security
                        `create_mate` is `RiskCategory.FILE_WRITE` (elevated + audited). Its parameters are \
                        GENERIC. The spawned Mate inherits the deployer policy and gateway screening. The \
                        Leader is responsible for the Mates it creates.
                        """,
                examples = {
                    "{\"groupId\": \"grp-1\", \"mateId\": \"coder-1\", \"skillset\": \"coding\"}",
                    "{\"groupId\": \"grp-1\", \"mateId\": \"tester-1\", \"skillset\": \"testing\"}",
                    "{\"groupId\": \"grp-1\", \"mateId\": \"reviewer-1\", \"skillset\": \"review\"}",
                    "{\"groupId\": \"grp-7\", \"mateId\": \"coder-2\", \"skillset\": \"coding\"}",
                    "{\"groupId\": \"grp-1\", \"mateId\": \"docs-1\", \"skillset\": \"docs\"}"
                })
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
        public String execute(@NonNull Args args) {
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

        @ToolDoc(
                description =
                        """
                        #### When to use
                        Use `remove_mate` to remove a Mate from an active group - when the Mate's work is done, \
                        it is misbehaving, or the group is being scaled down. In-flight nodes assigned to the \
                        removed Mate go to PENDING for re-assignment.

                        #### When NOT to use
                        - Do not remove a Mate with in-flight work you cannot re-assign; the nodes go PENDING \
                        but may stall.
                        - Do not remove the last Mate if work remains; either finish or disband.
                        - Do not call it with an unverified `groupId`/`mateId`.

                        #### Behavior
                        Removes the Mate `mateId` from group `groupId` via the spawner. Returns "mate removed". \
                        Any DAG nodes assigned to that Mate revert to PENDING for re-assignment.

                        #### Return format
                        The string `mate removed`.

                        #### Errors & edge cases
                        - `groupId`/`mateId` not found / invalid UUID -> exception.
                        - Removing a non-existent Mate -> spawner-dependent; confirm the id first.
                        - In-flight nodes revert to PENDING; re-dispatch them via `dispatchTask`.

                        #### Security
                        `remove_mate` is `RiskCategory.FILE_WRITE` (elevated + audited). Its parameters are \
                        GENERIC. It mutates group runtime state. Confirm ids before calling.
                        """,
                examples = {
                    "{\"groupId\": \"grp-1\", \"mateId\": \"coder-1\"}",
                    "{\"groupId\": \"grp-1\", \"mateId\": \"tester-1\"}",
                    "{\"groupId\": \"grp-7\", \"mateId\": \"coder-2\"}",
                    "{\"groupId\": \"grp-1\", \"mateId\": \"docs-1\"}",
                    "{\"groupId\": \"grp-1\", \"mateId\": \"reviewer-1\"}"
                })
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
        public String execute(@NonNull Args args) {
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

        @ToolDoc(
                description =
                        """
                        #### When to use
                        Use `dispatchTask` (Leader -> Mate) to push a Leader-authored task or revision \
                        instruction to a Mate. The Leader reasons over incoming messages and composes the \
                        instruction itself; this is not a pass-through. Posted to the Blackboard as \
                        TASK_DISPATCH.

                        #### When NOT to use
                        - Do not call `dispatchTask` as a Mate (only the Leader dispatches).
                        - Do not pass through user input verbatim as `instruction`; the Leader authors it.
                        - Do not dispatch to a Mate id that does not exist in the group.
                        - Do not use it for Mate -> Leader messages; use `postMessage`.

                        #### Behavior
                        Posts a TASK_DISPATCH message to the group's Blackboard, addressed to `mateId`. The \
                        payload is `<mateId>:<instruction>` (the mateId stands in as the nodeId for ad-hoc \
                        dispatch). Returns "dispatched". The target Mate picks it up on its next tick.

                        #### Return format
                        The string `dispatched`.

                        #### Errors & edge cases
                        - `groupId`/`mateId` not found / invalid UUID -> the post may still succeed against a \
                        valid group but the Mate will not exist; confirm ids first.
                        - The instruction should be self-contained; the Mate has only the Blackboard, not the \
                        Leader's full context.
                        - For DAG-tied dispatch, the payload's nodeId prefix is what the Mate parses; ad-hoc \
                        dispatch uses mateId as nodeId.

                        #### Security
                        `dispatchTask` is `RiskCategory.FILE_WRITE` (elevated + audited). Its parameters are \
                        GENERIC. The instruction is Leader-authored (not raw user input), reducing injection \
                        risk, but it is still audited. Do not embed secrets in instructions.
                        """,
                examples = {
                    "{\"groupId\": \"grp-1\", \"mateId\": \"coder-1\", \"instruction\": \"Implement X\"}",
                    "{\"groupId\": \"grp-1\", \"mateId\": \"tester-1\", \"instruction\": \"Write tests for the auth module\"}",
                    "{\"groupId\": \"grp-1\", \"mateId\": \"coder-1\", \"instruction\": \"Revise node-3: handle the null case\"}",
                    "{\"groupId\": \"grp-7\", \"mateId\": \"coder-2\", \"instruction\": \"Refactor the vault store\"}",
                    "{\"groupId\": \"grp-1\", \"mateId\": \"reviewer-1\", \"instruction\": \"Review the diff in src/auth\"}"
                })
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
        public String execute(@NonNull Args args) {
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

        @ToolDoc(
                description =
                        """
                        #### When to use
                        Use `postMessage` (Mate -> Leader) to send a typed message to the Leader: \
                        `ARTIFACT_REF` (point at a created/changed file), `LOG_REF` (point at a log/build \
                        output), `FEEDBACK` (report a blocker or result), `STATUS` (progress), or `ACCEPT` \
                        (accept a node). The receiver is always the Leader (hub-and-spoke).

                        #### When NOT to use
                        - Do not call `postMessage` as the Leader to a Mate; use `dispatchTask`.
                        - Do not post file CONTENTS in `payload` - paths or short summaries only. Mates must \
                        not exfiltrate large content through the Blackboard.
                        - Do not use an unknown `type`; it must be one of the enum values.
                        - Do not use it to update a DAG node's status; use `postStatus`.

                        #### Behavior
                        Posts a Blackboard message of the given `type` from the calling Mate (senderId from \
                        tool call context) to "LEADER", with `payload`. Returns "posted". The Leader reads it \
                        on its next tick.

                        #### Return format
                        The string `posted`.

                        #### Errors & edge cases
                        - `type` not a valid MessageType enum -> exception (valueOf fails). Valid: \
                        ARTIFACT_REF, LOG_REF, FEEDBACK, STATUS, ACCEPT.
                        - `groupId` invalid UUID -> exception.
                        - `payload` must be small (path/status/short feedback) - never file contents.
                        - senderId defaults to "mate" if tool call context is absent.

                        #### Security
                        `postMessage` is `RiskCategory.FILE_WRITE` (elevated + audited). Its parameters are \
                        GENERIC. The payload is subject to screening; never post secrets or full file contents \
                        - paths only. The Blackboard is retained for audit.
                        """,
                examples = {
                    "{\"groupId\": \"grp-1\", \"type\": \"FEEDBACK\", \"payload\": \"node-3 blocked on config\"}",
                    "{\"groupId\": \"grp-1\", \"type\": \"ARTIFACT_REF\", \"payload\": \"src/auth/Login.java\"}",
                    "{\"groupId\": \"grp-1\", \"type\": \"LOG_REF\", \"payload\": \"build/auth.log:42\"}",
                    "{\"groupId\": \"grp-1\", \"type\": \"STATUS\", \"payload\": \"node-1 50% done\"}",
                    "{\"groupId\": \"grp-1\", \"type\": \"ACCEPT\", \"payload\": \"node-2 accepted\"}",
                    "{\"groupId\": \"grp-7\", \"type\": \"FEEDBACK\", \"payload\": \"tests green for node-5\"}"
                })
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
    @ToolSecurity(risk = RiskCategory.FILE_WRITE)
    public static final class PostStatus implements NativeMcpTool<PostStatus.Args> {

        private final GroupRegistry registry;

        public PostStatus(GroupRegistry registry) {
            this.registry = registry;
        }

        @ToolDoc(
                description =
                        """
                        #### When to use
                        Use `postStatus` to update a DAG node's status: `PENDING`, `ASSIGNED`, `RUNNING`, \
                        `VERIFIED`, `FAILED`, or `STALE`. Persists in the group record. Used by Leader or Mate \
                        to reflect node progress.

                        #### When NOT to use
                        - Do not use `postStatus` to send a message; use `postMessage`.
                        - Do not use an unknown `status`; it must be a valid NodeState.
                        - Do not update a node you do not own without coordination.
                        - Do not call it with an unverified `groupId`/`nodeId`.

                        #### Behavior
                        Looks up the group `groupId`, finds the DAG node `nodeId`, and updates its state to \
                        `status` (persisted via the registry). Returns "ok" on success, "unknown group" or \
                        "node not found" otherwise.

                        #### Return format
                        `ok` on success. `unknown group` if the group id is not found. `node not found` if the \
                        node id is not in the group's DAG.

                        #### Errors & edge cases
                        - `groupId` invalid UUID or not in registry -> "unknown group".
                        - `nodeId` not in the DAG -> "node not found".
                        - `status` not a valid NodeState -> exception (valueOf fails). Valid: PENDING, \
                        ASSIGNED, RUNNING, VERIFIED, FAILED, STALE.
                        - Only the matching node's state changes; other fields are preserved.

                        #### Security
                        `postStatus` is `RiskCategory.FILE_WRITE` (elevated + audited). Its parameters are \
                        GENERIC. It mutates the group's DAG record. Confirm ids before calling.
                        """,
                examples = {
                    "{\"groupId\": \"grp-1\", \"nodeId\": \"node-3\", \"status\": \"VERIFIED\"}",
                    "{\"groupId\": \"grp-1\", \"nodeId\": \"node-1\", \"status\": \"RUNNING\"}",
                    "{\"groupId\": \"grp-1\", \"nodeId\": \"node-2\", \"status\": \"FAILED\"}",
                    "{\"groupId\": \"grp-1\", \"nodeId\": \"node-5\", \"status\": \"ASSIGNED\"}",
                    "{\"groupId\": \"grp-7\", \"nodeId\": \"node-1\", \"status\": \"PENDING\"}",
                    "{\"groupId\": \"grp-1\", \"nodeId\": \"node-4\", \"status\": \"STALE\"}"
                })
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
