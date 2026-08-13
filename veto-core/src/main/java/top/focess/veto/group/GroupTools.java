package top.focess.veto.group;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.identity.RoleToolFilter;
import top.focess.veto.agent.mcp.AgentTool;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolCallContext;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolDoc;

/**
 * The agent-facing group management tools (delegation_spawning.md, blackboard.md). Model B: the
 * caller of {@code create_group} <em>transforms</em> into the Leader in place (state, not type) and
 * authors the execution DAG node by node via {@code create_node} / {@code remove_node} (defined in
 * {@link DagTools}). The Leader tears the group down with {@code disband_group} (context-derived,
 * no id) which reverses the transform back to STANDALONE. {@code post_message} posts to the
 * Blackboard (Leader -> Mate, or a self-note); Mates do not get it - they report via their {@link
 * MateAgent} wrapper.
 *
 * <p>These are agent tools - they carry {@link top.focess.veto.agent.mcp.RiskCategory#AGENT}; the
 * Gateway returns {@code NotScreened}. Each tool resolves the caller's group from the {@link
 * ToolCallContext} (the calling agent leads exactly one group), so none of them takes a {@code
 * groupId} argument. Role gating is enforced by tool availability: {@code create_group} is offered
 * only to STANDALONE; {@code disband_group} / {@code post_message} only to the Leader.
 */
public final class GroupTools {

    private GroupTools() {}

    /** {@code create_group} - spawn a delegation. The calling agent transforms into the Leader. */
    @Component
    @ToolDoc(
            description =
                    "Spawn a delegation group for a task; you transform into its Leader "
                            + "and plan the work.",
            usage =
                    """
                    #### When to use
                    Use `create_group` when a goal is too large or spans too many domains for one \
                    agent and you want to delegate it to a group you lead. Pass a short brief of \
                    the work; you become the Leader and plan from there.

                    #### When NOT to use
                    - Do not use it for work you can finish yourself - a group adds coordination cost.
                    - Do not use it from inside a group; Leaders and Mates cannot spawn another group.
                    - Do not try to supply a plan up front - you build it node by node as Leader, \
                    via `create_node`.

                    #### Behavior
                    Transforms you into the Leader of a new, empty group. Your context is rewound to \
                    0; then an AGENT_INIT turn (Leader system prompt + Leader tool set: read-only \
                    investigation tools, the node-authoring tools, `post_message`, `disband_group`) \
                    is appended; then a compact turn (the compaction of your previous session); then \
                    `task` as your planning brief. You are promoted to the top-tier model. Mates are \
                    provisioned lazily by the engine as your nodes dispatch. The result string of \
                    this call is discarded with the rewind - only a failure keeps you in the \
                    single-agent loop with the reason.

                    #### Return format
                    On success - empty (the call's result is discarded with the rewind; you \
                    continue as the Leader from the brief).
                    On refusal:
                      Group not created: <reason and what to do next>

                    #### Errors & edge cases
                    - Blank `task` -> not created; pass a real brief.
                    - Already leading or inside a group -> the tool is not offered at all.

                    #### Security
                    Agent tool (`RiskCategory.AGENT`). The Gateway returns `NotScreened`. Available \
                    only in the single-agent loop - not offered to Leaders or Mates.
                    """,
            examples = {
                "{\"task\": \"Fix the authentication bug in UserService\"}",
                "{\"task\": \"Rewrite the persistence layer\"}"
            },
            returnExamples = {
                "",
                "Group not created: blank brief. Pass a real description of the work."
            })
    public static final class CreateGroup implements AgentTool<CreateGroup.Args> {

        private final @NonNull GroupSpawner spawner;
        private final @NonNull LeaderBinding leaderBinding;
        private final @NonNull RoleToolFilter roleToolFilter;

        public CreateGroup(
                @NonNull GroupSpawner spawner,
                @NonNull LeaderBinding leaderBinding,
                @NonNull RoleToolFilter roleToolFilter) {
            this.spawner = spawner;
            this.leaderBinding = leaderBinding;
            this.roleToolFilter = roleToolFilter;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "Short brief of the work to be done (seeds your investigation as Leader).")
                        @Nullable String task) {}

        @Override
        public @NonNull String getName() {
            return "create_group";
        }

        @Override
        public @NonNull String getDescription() {
            return "Spawn a delegation group for a task; you transform into its Leader "
                    + "and plan the work.";
        }

        @Override
        public @NonNull Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public @NonNull String execute(@NonNull Args args) {
            String task = args.task() == null ? "" : args.task().strip();
            if (task.isBlank()) {
                return "Group not created: blank brief. Pass a real description of the work.";
            }
            // Resolve the calling STANDALONE's identity (the group owner / future Leader).
            ToolCallContext ctx = ToolCallContextHolder.get();
            String leaderId = ctx != null ? ctx.agentId() : "leader";
            String userId = ctx != null ? ctx.userId().toString() : "default";
            String owner = ctx != null ? ctx.owner() : null;

            // Register an empty group - no DAG yet, no Mates. The Leader (the transformed caller)
            // authors the DAG node by node via create_node; the engine provisions Mates lazily on
            // dispatch.
            Group g = spawner.registerEmptyGroup(leaderId, userId, owner, task);

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

    /** {@code disband_group} - tear down the active group and return the agent to STANDALONE. */
    @Component
    @ToolDoc(
            description = "Tear down your active group and return to single-agent autonomous mode.",
            usage =
                    """
                    #### When to use
                    Use `disband_group` when the delegated work is complete or the user explicitly \
                    requests it. Reverses the transform: you become STANDALONE again. Only the Leader \
                    can call this.

                    #### When NOT to use
                    - Do not disband while Mates are still running - wait for their status.
                    - Do not disband without user request unless all DAG nodes are VERIFIED.

                    #### Behavior
                    Resolves your group from your context (no id argument). Rewinds context to 0 + \
                    AGENT_INIT with your STANDALONE persona, appends a compaction of the Leader \
                    session + an outcome brief, and restores your STANDALONE tool set + binding. The \
                    Blackboard is retained for audit; all Mates are deprovisioned; the DAG is marked \
                    closed.

                    #### Return format
                    On success - empty (the call's result is discarded with the rewind; you continue \
                    as STANDALONE from the outcome brief).
                    On refusal:
                      Group not disbanded: <reason and what to do next>

                    #### Errors & edge cases
                    No active group in your context -> refusal. Mates still RUNNING -> the disband \
                    proceeds (in-flight work may be lost).

                    #### Security
                    Agent tool (`RiskCategory.AGENT`). The Gateway does not screen it. Leader-only.
                    """,
            examples = {"{}"},
            returnExamples = {
                "(empty on success - you continue as STANDALONE from the outcome brief)",
                "Group not disbanded: Mates still RUNNING - wait for them to finish first."
            })
    public static final class DisbandGroup implements AgentTool<DisbandGroup.Args> {

        private final @NonNull GroupSpawner spawner;
        private final @NonNull GroupRegistry registry;

        public DisbandGroup(@NonNull GroupSpawner spawner, @NonNull GroupRegistry registry) {
            this.spawner = spawner;
            this.registry = registry;
        }

        public record Args() {}

        @Override
        public @NonNull String getName() {
            return "disband_group";
        }

        @Override
        public @NonNull String getDescription() {
            return "Tear down your active group and return to single-agent autonomous mode.";
        }

        @Override
        public @NonNull Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public @NonNull String execute(@NonNull Args args) {
            ToolCallContext ctx = ToolCallContextHolder.get();
            UUID groupId = ctx != null ? ctx.groupId() : null;
            if (groupId == null) {
                return "Group not disbanded: no active group in your context. disband_group is "
                        + "a Leader tool inside a group.";
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

        /**
         * Builds the outcome brief seeded into the now-STANDALONE agent's context after disband.
         */
        private static @NonNull String buildDisbandBrief(@Nullable Group g) {
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
            }
            sb.append("You are back in single-agent autonomous mode. Continue from here.");
            return sb.toString();
        }
    }

    /** {@code post_message} - post a typed message to the group's Blackboard. */
    @Component
    @ToolDoc(
            description =
                    "Post a typed message to your group's Blackboard (Leader -> Mate, or a self-note).",
            usage =
                    """
                    #### When to use
                    Use `post_message` to communicate via the Blackboard - dispatch an ad-hoc \
                    instruction to a Mate, post a status note, or record feedback. The Leader reasons \
                    over Mate reports, then writes its own message (not a pass-through).

                    #### When NOT to use
                    - Do not echo Mate messages back - decide, don't pass-through.
                    - Do not post full file contents - only paths / short payloads.
                    - Do not use it for ordinary DAG dispatch - `create_node` dispatches automatically \
                    as dependencies verify.

                    #### Behavior
                    Posts a message to the Blackboard addressed to `receiver` (a Mate id, or `LEADER` \
                    for a self-note). Message types: TASK_DISPATCH, ARTIFACT_REF, LOG_REF, FEEDBACK, \
                    STATUS, ACCEPT. Resolves the group + sender from your context (no id arguments). \
                    Payloads must be small - no file contents, paths only for artifacts/logs.

                    #### Return format
                    On success - `posted`.
                    On refusal:
                      Not posted: <reason and what to do next>

                    #### Errors & edge cases
                    No active group in your context -> refusal. Unknown `type` -> error. Oversized \
                    payload -> warn (context saturation risk).

                    #### Security
                    Agent tool (`RiskCategory.AGENT`). The Gateway does not screen it. Leader-only. \
                    Hub-and-spoke: the Leader addresses a single Mate by id.
                    """,
            examples = {
                "{\"type\": \"TASK_DISPATCH\", \"receiver\": \"mate-coder\", \"payload\": \"node-5: Revise the JWT validation to check expiry\"}",
                "{\"type\": \"STATUS\", \"receiver\": \"LEADER\", \"payload\": \"node-5 re-planned; new node node-5b created\"}"
            },
            returnExamples = {
                "posted",
                "Not posted: unknown message type 'BROADCAST' - use one of"
                        + " TASK_DISPATCH, ARTIFACT_REF, LOG_REF, FEEDBACK, STATUS, ACCEPT."
            })
    public static final class PostMessage implements AgentTool<PostMessage.Args> {

        private final @NonNull Blackboard blackboard;

        public PostMessage(@NonNull Blackboard blackboard) {
            this.blackboard = blackboard;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "Message type: TASK_DISPATCH, ARTIFACT_REF, LOG_REF, FEEDBACK, STATUS, ACCEPT.")
                        @Nullable String type,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Receiver id (a Mate id, or 'LEADER' for a self-note).")
                        @Nullable String receiver,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "Small payload (path / status / short feedback). No file contents - paths only.")
                        @Nullable String payload) {}

        @Override
        public @NonNull String getName() {
            return "post_message";
        }

        @Override
        public @NonNull String getDescription() {
            return "Post a typed message to your group's Blackboard (Leader -> Mate, or a self-note).";
        }

        @Override
        public @NonNull Class<Args> getArgsClass() {
            return Args.class;
        }

        @Override
        public @NonNull String execute(@NonNull Args args) {
            ToolCallContext ctx = ToolCallContextHolder.get();
            UUID groupId = ctx != null ? ctx.groupId() : null;
            if (groupId == null) {
                return "Not posted: no active group in your context. post_message is a Leader "
                        + "tool inside a group.";
            }
            // The Blackboard identifies the Leader by the literal "LEADER" (its hub-and-spoke guard
            // + the orchestrator's ingest both key on it), so the Leader posts as "LEADER".
            BlackboardMessage.MessageType type;
            try {
                type = BlackboardMessage.MessageType.valueOf(args.type());
            } catch (IllegalArgumentException e) {
                return "Not posted: unknown message type '" + args.type() + "'.";
            }
            String receiver = args.receiver() == null ? "LEADER" : args.receiver();
            blackboard.post(
                    new BlackboardMessage(
                            UUID.randomUUID().toString(),
                            groupId,
                            "LEADER",
                            receiver,
                            type,
                            args.payload(),
                            0));
            return "posted";
        }
    }
}
