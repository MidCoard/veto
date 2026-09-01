package top.focess.veto.group;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
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
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolErrors;
import top.focess.veto.agent.mcp.ToolResultFormat;

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
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description =
                    "Spawn a delegation group for a task; you transform into its Leader "
                            + "and plan the work.",
            behavior =
                    """
                    Transforms you into the Leader of a new, empty group. Your context is rewound, a \
                    Leader AGENT_INIT turn and the available Leader tools are installed, a non-empty \
                    compaction summary is appended when one is produced, and `task` becomes your \
                    planning brief. The Leader model comes from the deployer's Leader-tier binding. \
                    Mates are provisioned lazily as nodes become dispatchable. The success result is \
                    discarded by the transform; a refusal leaves you in the single-agent loop.
                    """,
            whenToUse =
                    """
                    Use `create_group` when a goal is too large or spans too many domains for one \
                    agent and you want to delegate it to a group you lead. Pass a short brief of \
                    the work; you become the Leader and plan from there.
                    """,
            whenNotToUse =
                    """
                    - Do not use it for work you can finish yourself - a group adds coordination cost.
                    - Do not use it from inside a group; the tool is not offered to Leaders or Mates.
                    - Do not try to supply a plan up front - you build it node by node as Leader, \
                    via `create_node`.
                    """,
            resultContract =
                    """
                    On success - empty (the call's result is discarded with the rewind; you \
                    continue as the Leader from the brief).
                    On refusal:
                      Group not created: <reason and what to do next>
                    """,
            errorsAndEdgeCases =
                    """
                    - Blank `task` -> not created; pass a real brief.
                    - Already leading or inside a group -> the tool is not offered at all.
                    """,
            security =
                    """
                    Agent tool (`RiskCategory.AGENT`). The Gateway returns `NotScreened`. Available \
                    only in the single-agent loop - not offered to Leaders or Mates.
                    """,
            examples = {
                "{\"task\": \"Redesign the persistence layer, migrate its callers, and verify the affected modules\"}",
                "{\"task\": \"Rewrite the persistence layer\"}"
            },
            returnExamples = {""})
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
                        @NonNull String task) {}

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
            return ToolDocs.nonNullClass(Args.class);
        }

        @Override
        public @NonNull String execute(@NonNull Args args) {
            String task = args.task().strip();
            if (task.isBlank()) {
                return ToolErrors.failure(
                        "Group not created: blank brief. Pass a real description of the work.");
            }
            // Resolve the calling STANDALONE's identity (the group owner / future Leader).
            ToolCallContext ctx = ToolCallContextHolder.get();
            if (ctx == null) {
                return ToolErrors.failure(
                        "Group not created: no authenticated session owner is available.");
            }
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
                            leaderId, userId, owner, task, ctx.toolResultPresentation());

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
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description = "Tear down your active group and return to single-agent autonomous mode.",
            behavior =
                    """
                    Resolves your group from your context (no id argument), deprovisions its Mates, \
                    and marks the group DISBANDED. It then rewinds to an AGENT_INIT with your \
                    STANDALONE persona, restores the STANDALONE tools and model binding, appends a \
                    non-empty compaction summary when one is produced, and adds an outcome brief. \
                    The Blackboard and recorded DAG remain available for audit.
                    """,
            whenToUse =
                    """
                    Use `disband_group` when the delegated work is complete or the user explicitly \
                    requests it. Reverses the transform: you become STANDALONE again. Only the Leader \
                    can call this.
                    """,
            whenNotToUse =
                    """
                    - Prefer waiting for Mate status when practical; disbanding stops any remaining Mates.
                    - Do not disband without user request unless all DAG nodes are VERIFIED.
                    """,
            resultContract =
                    """
                    On success - empty (the call's result is discarded with the rewind; you continue \
                    as STANDALONE from the outcome brief).
                    On refusal:
                      Group not disbanded: <reason and what to do next>
                    """,
            errorsAndEdgeCases =
                    """
                    No active group in your context -> refusal. Mates still RUNNING -> the disband \
                    proceeds (in-flight work may be lost).
                    """,
            security =
                    """
                    Agent tool (`RiskCategory.AGENT`). The Gateway does not screen it. Leader-only.
                    """,
            examples = {"{}"},
            returnExamples = {""})
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
            return ToolDocs.nonNullClass(Args.class);
        }

        @Override
        public @NonNull String execute(@NonNull Args args) {
            ToolCallContext ctx = ToolCallContextHolder.get();
            UUID groupId = ctx != null ? ctx.groupId() : null;
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

        /**
         * Builds the outcome brief seeded into the now-STANDALONE agent's context after disband.
         */
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
    }

    /** {@code post_message} - post a typed message to the group's Blackboard. */
    @Component
    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description =
                    "Post a typed message to your group's Blackboard (Leader -> Mate, or a self-note).",
            behavior =
                    """
                    Posts a message as `LEADER` to `receiver`. Omit `receiver` to default to `LEADER` \
                    for a self-note; otherwise use an active Mate id. Unknown receivers are rejected. \
                    Message types are TASK_DISPATCH, ARTIFACT_REF, LOG_REF, FEEDBACK, STATUS, and \
                    ACCEPT. The group is resolved from your context. Payloads must be non-blank and \
                    at most 4096 characters. Use paths rather than full file contents for artifacts/logs.
                    """,
            whenToUse =
                    """
                    Use `post_message` to communicate via the Blackboard - dispatch an ad-hoc \
                    instruction to a Mate, post a status note, or record feedback. The Leader reasons \
                    over Mate reports, then writes its own message (not a pass-through).
                    """,
            whenNotToUse =
                    """
                    - Do not echo Mate messages back - decide, don't pass-through.
                    - Do not post full file contents - only paths / short payloads.
                    - Do not use it for ordinary DAG dispatch - `create_node` dispatches automatically \
                    as dependencies verify.
                    """,
            resultContract =
                    """
                    On success - `posted`.
                    On refusal:
                      Not posted: <reason and what to do next>
                    """,
            errorsAndEdgeCases =
                    """
                    Type names are case-sensitive enum values, and a Mate receiver must already belong to the \
                    active group. No active group indicates a role/context mismatch; do not retry until the \
                    Leader context is restored.
                    """,
            security =
                    """
                    Agent tool (`RiskCategory.AGENT`). The Gateway does not screen it. Leader-only. \
                    Hub-and-spoke: the Leader addresses a single Mate by id.
                    """,
            examples = {
                "{\"type\": \"TASK_DISPATCH\", \"receiver\": \"mate-coder\", \"payload\": \"node-5: Revise the JWT validation to check expiry\"}",
                "{\"type\": \"STATUS\", \"receiver\": \"LEADER\", \"payload\": \"node-5 re-planned; new node node-5b created\"}"
            },
            returnExamples = {"posted"})
    public static final class PostMessage implements AgentTool<PostMessage.Args> {

        private static final int MAX_PAYLOAD_CHARS = 4096;

        private final @NonNull Blackboard blackboard;
        private final @NonNull GroupRegistry groupRegistry;

        public PostMessage(@NonNull Blackboard blackboard, @NonNull GroupRegistry groupRegistry) {
            this.blackboard = blackboard;
            this.groupRegistry = groupRegistry;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "Message type: TASK_DISPATCH, ARTIFACT_REF, LOG_REF, FEEDBACK, STATUS, ACCEPT.")
                        @NonNull String type,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Receiver id (a Mate id, or 'LEADER' for a self-note).")
                        String receiver,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "Non-blank payload up to 4096 characters. Prefer paths over file contents.")
                        @NonNull String payload) {}

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
            return ToolDocs.nonNullClass(Args.class);
        }

        @Override
        public @NonNull String execute(@NonNull Args args) {
            ToolCallContext ctx = ToolCallContextHolder.get();
            UUID groupId = ctx != null ? ctx.groupId() : null;
            if (groupId == null) {
                return ToolErrors.failure(
                        "Not posted: no active group in your context. post_message is a Leader "
                                + "tool inside a group.");
            }
            // The Blackboard identifies the Leader by the literal "LEADER" (its hub-and-spoke guard
            // + the orchestrator's ingest both key on it), so the Leader posts as "LEADER".
            BlackboardMessage.MessageType type;
            String typeName = args.type();
            if (typeName.isBlank()) {
                return ToolErrors.failure("Not posted: message type must not be blank.");
            }
            try {
                type =
                        top.focess.veto.util.Nullness.requireNonNull(
                                BlackboardMessage.MessageType.valueOf(typeName));
            } catch (IllegalArgumentException e) {
                return ToolErrors.failure("Not posted: unknown message type '" + typeName + "'.");
            }
            String receiver = args.receiver() == null ? "LEADER" : args.receiver();
            Group group = groupRegistry.get(groupId);
            if (group == null
                    || (!"LEADER".equals(receiver) && !group.mates().containsKey(receiver))) {
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
                            type,
                            payload,
                            0));
            return "posted";
        }
    }
}
