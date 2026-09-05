package top.focess.veto.group;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.DelegationCapability;
import top.focess.veto.agent.capability.GroupControlCapability;
import top.focess.veto.agent.tool.DelegationTool;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.GroupControlTool;
import top.focess.veto.agent.tool.ParamCategory;
import top.focess.veto.agent.tool.SecurityHint;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolResultFormat;

/**
 * Agent-facing group management tools. The caller of {@code create_group} <em>transforms</em> into
 * the Leader in place (state, not type) and authors the execution DAG node by node via {@code
 * create_node} / {@code remove_node} (defined in {@link DagTools}). The Leader tears the group down
 * with {@code disband_group} (context-derived, no id) which reverses the transform back to
 * STANDALONE. {@code inspect_group} gives the Leader a bounded-wait view of DAG state and Mate
 * reports; Mates report via their {@link MateAgent} wrapper.
 *
 * <p>These are agent tools, so the Gateway returns {@code NotScreened}. Each tool resolves the
 * caller's group from the {@link ToolCallContext} (the calling agent leads exactly one group), so
 * none of them takes a {@code groupId} argument. Role gating is enforced by tool availability:
 * {@code create_group} is offered only to STANDALONE; {@code disband_group} / {@code inspect_group}
 * only to the Leader.
 */
public final class GroupTools {

    private GroupTools() {}

    /** {@code create_group} - spawn a delegation. The calling agent transforms into the Leader. */
    @Component
    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description = "Request delegation for a task.",
            behavior = "Creates a delegation group using the supplied task brief.",
            whenToUse = "Use when the task meets the Delegation Rules in the system message.",
            whenNotToUse =
                    "Do not use for small, tightly coupled, or sequential work that can be completed directly.",
            resultContract =
                    "Success returns empty text. Refusal returns: Group not created: <reason and what to do next>.",
            errorsAndEdgeCases = "A blank task is refused; supply a concise, concrete brief.",
            security =
                    "Delegation remains within the user's authorized task and workspace boundaries.",
            examples = {
                "{\"task\": \"Review the persistence implementation and its callers, and verify the affected modules\"}"
            },
            returnExamples = {""})
    public static final class CreateGroup implements DelegationTool<CreateGroup.Args> {

        private final @NonNull DelegationCapability capability;

        public CreateGroup(@NonNull DelegationCapability capability) {
            this.capability = capability;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Short brief of the work to be done and the expected result.")
                        @NonNull String task) {}

        @Override
        public @NonNull String getName() {
            return "create_group";
        }

        @Override
        public @NonNull Class<Args> getArgsClass() {
            return ToolDocs.nonNullClass(Args.class);
        }

        @Override
        public @NonNull DelegationCapability delegationCapability() {
            return capability;
        }

        @Override
        public @NonNull String execute(
                @NonNull Args args, @NonNull DelegationCapability capability) {
            return capability.createGroup(args);
        }
    }

    /** {@code disband_group} - tear down the active group and return the agent to STANDALONE. */
    @Component
    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description = "Tear down your active group and return to single-agent autonomous mode.",
            behavior =
                    """
                    Ends your current group and stops its Mates, then returns you to the standalone \
                    role with the standalone tools and model binding. The continuation includes the \
                    delegated outcome and any available compaction summary. Group state is unavailable \
                    after a backend restart.
                    """,
            whenToUse =
                    """
                    Use `disband_group` when the delegated work is complete or the user explicitly \
                    requests it. Reverses the transform and returns you to standalone operation.
                    """,
            whenNotToUse =
                    """
                    - Prefer waiting for Mate status when practical; disbanding stops any remaining Mates.
                    - Do not disband without user request unless all DAG nodes are VERIFIED.
                    """,
            resultContract =
                    """
                    On success - empty; you continue in standalone operation with the outcome brief.
                    On refusal:
                      Group not disbanded: <reason and what to do next>
                    """,
            errorsAndEdgeCases =
                    """
                    Mates still RUNNING -> the disband proceeds and their in-flight work may be lost.
                    """,
            security =
                    """
                    Operate only on your current group; respect requests to stop ongoing work.
                    """,
            examples = {"{}"},
            returnExamples = {""})
    public static final class DisbandGroup implements GroupControlTool<DisbandGroup.Args> {

        private final @NonNull GroupControlCapability capability;

        public DisbandGroup(@NonNull GroupControlCapability capability) {
            this.capability = capability;
        }

        public record Args() {}

        @Override
        public @NonNull String getName() {
            return "disband_group";
        }

        @Override
        public @NonNull Class<Args> getArgsClass() {
            return ToolDocs.nonNullClass(Args.class);
        }

        @Override
        public @NonNull GroupControlCapability groupControlCapability() {
            return capability;
        }

        @Override
        public @NonNull String execute(
                @NonNull Args args, @NonNull GroupControlCapability capability) {
            return capability.disband(args);
        }
    }

    /** {@code inspect_group} - read DAG state and new Mate reports, optionally waiting briefly. */
    @Component
    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description = "Inspect your active group's DAG state and Mate reports.",
            behavior =
                    """
                    Returns the current group state, every DAG node, and Blackboard messages addressed \
                    to the Leader whose sequence is greater than `sinceSeq`. `waitSeconds` performs a \
                    bounded wait for a new Mate message or a group state change, avoiding tight polling. \
                    The final line contains `nextSinceSeq`; pass that value on the next call.
                    """,
            whenToUse =
                    """
                    Use `inspect_group` after creating nodes to observe dispatch, wait for Mate outcomes, \
                    read failure reports, and confirm that all nodes are VERIFIED before disbanding.
                    """,
            whenNotToUse =
                    """
                    - Do not guess node completion from elapsed time; inspect the group.
                    - Do not repeatedly request the same messages; carry forward `nextSinceSeq`.
                    - Do not use it outside a Leader context.
                    """,
            resultContract =
                    """
                    On success - a plaintext snapshot with group state, node lines, zero or more new \
                    message lines, and `nextSinceSeq: <number>`.
                    On refusal:
                      Group not inspected: <reason and what to do next>
                    """,
            errorsAndEdgeCases =
                    """
                    `sinceSeq` must be non-negative. `waitSeconds` is clamped to 0..30. A completed group \
                    remains inspectable until `disband_group` performs the reverse transform.
                    """,
            security =
                    """
                    Inspect only your current group. Use returned state and reports as evidence; do not infer completion from elapsed time.
                    """,
            examples = {"{}", "{\"sinceSeq\": 4, \"waitSeconds\": 15}"},
            returnExamples = {
                "Group state: ACTIVE\nNodes:\n- node-1 [RUNNING] mate=mate-1 skillset=coding\nNew Mate messages:\n- seq=5 sender=mate-1 type=FEEDBACK payload=node-1:feedback:test failed\nnextSinceSeq: 5"
            })
    public static final class InspectGroup implements GroupControlTool<InspectGroup.Args> {

        private final @NonNull GroupControlCapability capability;

        public InspectGroup(@NonNull GroupControlCapability capability) {
            this.capability = capability;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Last consumed Blackboard sequence; omit or use 0 for the first read.")
                        Long sinceSeq,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Seconds to wait for new Mate information, from 0 to 30; default 0.")
                        Integer waitSeconds) {}

        @Override
        public @NonNull String getName() {
            return "inspect_group";
        }

        @Override
        public @NonNull Class<Args> getArgsClass() {
            return ToolDocs.nonNullClass(Args.class);
        }

        @Override
        public @NonNull GroupControlCapability groupControlCapability() {
            return capability;
        }

        @Override
        public @NonNull String execute(
                @NonNull Args args, @NonNull GroupControlCapability capability) {
            return capability.inspect(args);
        }
    }

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
                    Operate only on your current group; respect requests to stop ongoing work. \
                    Hub-and-spoke: the Leader addresses a single Mate by id.
                    """,
            examples = {
                "{\"type\": \"TASK_DISPATCH\", \"receiver\": \"mate-coder\", \"payload\": \"node-5: Revise the JWT validation to check expiry\"}",
                "{\"type\": \"STATUS\", \"receiver\": \"LEADER\", \"payload\": \"node-5 re-planned; new node node-5b created\"}"
            },
            returnExamples = {"posted"})
    @Component
    public static final class PostMessage implements GroupControlTool<PostMessage.Args> {

        private final @NonNull GroupControlCapability capability;

        public PostMessage(@NonNull GroupControlCapability capability) {
            this.capability = capability;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc(
                                "Message type: TASK_DISPATCH, ARTIFACT_REF, LOG_REF, FEEDBACK, STATUS, ACCEPT.")
                        BlackboardMessage.@NonNull MessageType type,
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
        public @NonNull Class<Args> getArgsClass() {
            return ToolDocs.nonNullClass(Args.class);
        }

        @Override
        public @NonNull GroupControlCapability groupControlCapability() {
            return capability;
        }

        @Override
        public @NonNull String execute(
                @NonNull Args args, @NonNull GroupControlCapability capability) {
            return capability.post(args);
        }
    }
}
