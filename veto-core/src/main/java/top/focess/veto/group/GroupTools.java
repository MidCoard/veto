package top.focess.veto.group;

import java.util.List;
import java.util.concurrent.TimeUnit;
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
import top.focess.veto.agent.tool.ToolErrors;
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

    private static final int MAX_PAYLOAD_CHARS = 4096;

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
            String task = args.task().strip();
            if (task.isBlank())
                return ToolErrors.failure(
                        "Group not created: blank brief. Pass a real description of the work.");
            capability.createGroup(task);
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
            GroupSnapshot group = capability.snapshot();
            if (group == null) {
                return ToolErrors.failure(
                        "Group not disbanded: no active group in your context. disband_group is "
                                + "a Leader tool inside a group.");
            }
            // Summarize the group's outcome for the reverse-transform brief (verified nodes' \
            // results), then tear the group down.
            String brief = buildDisbandBrief(group);
            capability.disband(brief);
            // Request the reverse transform: the runner rewinds, restores the STANDALONE persona +
            // binding, and re-injects the outcome brief so the agent continues autonomously.

            return "";
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
            GroupSnapshot initial = capability.snapshot();
            if (initial == null) {
                return ToolErrors.failure(
                        "Group not inspected: no active group in your context. inspect_group is a "
                                + "Leader tool inside a group.");
            }
            Long requestedSince = args.sinceSeq();
            long since = requestedSince == null ? 0 : requestedSince;
            if (since < 0) {
                return ToolErrors.failure("Group not inspected: sinceSeq must be non-negative.");
            }
            Integer requestedWait = args.waitSeconds();
            int waitSeconds = requestedWait == null ? 0 : requestedWait;
            waitSeconds = Math.max(0, Math.min(30, waitSeconds));
            GroupSnapshot group = initial;
            List<BlackboardMessage> messages = capability.messages(since);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds);
            while (messages.isEmpty()
                    && group.state() == initial.state()
                    && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolErrors.failure("Group not inspected: wait interrupted.");
                }
                GroupSnapshot refreshed = capability.snapshot();
                if (refreshed == null) {
                    return ToolErrors.failure("Group not inspected: group record disappeared.");
                }
                group = refreshed;
                messages = capability.messages(since);
            }
            return render(group, messages, since);
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
            GroupSnapshot group = capability.snapshot();
            if (group == null) {
                return ToolErrors.failure(
                        "Not posted: no active group in your context. post_message is a Leader "
                                + "tool inside a group.");
            }
            // The Blackboard identifies the Leader by the literal "LEADER" (its hub-and-spoke guard
            // + the orchestrator's ingest both key on it), so the Leader posts as "LEADER".
            String requestedReceiver = args.receiver();
            String receiver = requestedReceiver == null ? "LEADER" : requestedReceiver;
            if (group.state() != Group.GroupState.ACTIVE) {
                return ToolErrors.failure("Not posted: group is no longer active.");
            }
            if (!"LEADER".equals(receiver) && !group.mates().containsKey(receiver)) {
                return ToolErrors.failure("Not posted: unknown receiver '" + receiver + "'.");
            }
            String payload = args.payload();
            if (payload.isBlank()) {
                return ToolErrors.failure("Not posted: payload must not be blank.");
            }
            if (payload.length() > MAX_PAYLOAD_CHARS) {
                return ToolErrors.failure("Not posted: payload exceeds 4096 characters.");
            }
            capability.post(receiver, args.type(), payload);
            return "posted";
        }
    }

    private static @NonNull String buildDisbandBrief(GroupSnapshot g) {
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
        for (DagNode n : g.nodes()) {
            sb.append("  - ").append(n.nodeId()).append(" (").append(n.state());
            if (n.assignedMateId() != null) {
                sb.append(", mate: ").append(n.assignedMateId());
            }
            sb.append("): ").append(n.description()).append('\n');
            if (n.result() instanceof DagNode.ResultArtifact artifact) {
                sb.append("    Artifact: ").append(artifact.artifactPath()).append('\n');
            } else if (n.result() instanceof DagNode.ResultSuccess success) {
                sb.append("    Mate report: ").append(success.summary()).append('\n');
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

    private static @NonNull String render(
            @NonNull GroupSnapshot group,
            @NonNull List<@NonNull BlackboardMessage> messages,
            long since) {
        StringBuilder result = new StringBuilder();
        result.append("Group state: ").append(group.state()).append('\n');
        result.append("Nodes:\n");
        if (group.nodes().isEmpty()) {
            result.append("- (none)\n");
        }
        for (DagNode node : group.nodes()) {
            result.append("- ")
                    .append(node.nodeId())
                    .append(" [")
                    .append(node.state())
                    .append("] mate=")
                    .append(node.assignedMateId() == null ? "(unassigned)" : node.assignedMateId())
                    .append(" skillset=")
                    .append(node.requiredSkillset())
                    .append('\n');
            if (node.result() instanceof DagNode.ResultSuccess success) {
                result.append("  report: ").append(oneLine(success.summary())).append('\n');
            } else if (node.result() instanceof DagNode.ResultFailure failure) {
                result.append("  failure: ").append(oneLine(failure.feedback())).append('\n');
            }
        }
        result.append("New Mate messages:\n");
        if (messages.isEmpty()) {
            result.append("- (none)\n");
        }
        long next = since;
        for (BlackboardMessage message : messages) {
            next = Math.max(next, message.turnSeq());
            var report = GroupOrchestrator.resultFromMate(message);
            String payload =
                    report instanceof DagNode.ResultSuccess success
                            ? success.summary()
                            : message.payload();
            result.append("- seq=")
                    .append(message.turnSeq())
                    .append(" sender=")
                    .append(message.senderId())
                    .append(" type=")
                    .append(message.type())
                    .append(" payload=")
                    .append(oneLine(payload))
                    .append('\n');
        }
        result.append("nextSinceSeq: ").append(next);
        return result.toString();
    }

    private static @NonNull String oneLine(@NonNull String value) {
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }
}
