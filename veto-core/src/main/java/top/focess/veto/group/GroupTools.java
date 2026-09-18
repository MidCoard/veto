package top.focess.veto.group;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.DelegationCapability;
import top.focess.veto.agent.capability.GroupControlCapability;
import top.focess.veto.agent.loop.PromptLibrary;
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
            behavior =
                    "Starts real collaboration using the supplied brief. Participants and work are arranged in the next stage.",
            whenToUse = "Use when the task meets the Delegation Rules in the system message.",
            whenNotToUse =
                    "Unless the user explicitly requests collaborators, prefer direct execution for small or tightly coupled work.",
            resultContract =
                    "Success returns empty text. Refusal returns: Group not created: <reason and what to do next>.",
            errorsAndEdgeCases = "A blank task is refused; supply a concise, concrete brief.",
            security =
                    "Delegation remains within the user's authorized task and workspace boundaries.",
            examples = {
                "{\"task\": \"Review the persistence implementation and its callers, and verify the affected modules\"}",
                "{\"task\": \"Migrate the billing module from JPA to jOOQ; deliver the converted repositories and passing integration tests\"}",
                "{\"task\": \"Compare the three shortlisted message queues for the notification service and recommend one with a rationale\"}"
            },
            returnExamples = {"", "", ""})
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
                    Use `disband_group` only when the user explicitly requests that the group be \
                    disbanded or asks to return to single-agent operation.
                    """,
            whenNotToUse =
                    """
                    - Prefer waiting for Mate status when practical; disbanding stops any remaining Mates.
                    - Do not disband merely because all DAG nodes are COMPLETED. Answer the user \
                      as Leader and retain the group for follow-up work.
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
                    Use `inspect_group` for an on-demand state or report lookup, \
                    read failure reports, and gather completed reports before answering the user.
                    """,
            whenNotToUse =
                    """
                    - Do not use it to wait for outcomes; Monitor observations deliver those automatically.
                    - Do not inspect a newly created empty group instead of creating its members.
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
                    remains available for inspection and new tasks. Completion does not require disbanding.
                    """,
            security =
                    """
                    Inspect only your current group. Use returned state and reports as evidence; do not infer completion from elapsed time.
                    """,
            examples = {
                "{}",
                "{\"sinceSeq\": 4}",
                "{\"waitSeconds\": 30}",
                "{\"sinceSeq\": 4, \"waitSeconds\": 15}"
            },
            returnExamples = {
                "Group state: ACTIVE\nA completed task means the assigned mate returned a report; it does not imply independent verification.\nMates:\n- 3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f: review backend session isolation\nTasks:\n- node-1 [RUNNING] mate=3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f skillset=coding dependsOn=[]\nImplement JWT login in UserService\nMessages are historical observations; task state above is authoritative.\nNew mate messages:\n- seq=2 sender=3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f type=FEEDBACK\ndispatch=(uncorrelated) currentDispatch=false\npayload=node-1:feedback:started review\nnextSinceSeq: 3",
                "Group state: ACTIVE\nA completed task means the assigned mate returned a report; it does not imply independent verification.\nMates:\n- 3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f: review backend session isolation\nTasks:\n- node-1 [RUNNING] mate=3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f skillset=coding dependsOn=[]\nImplement JWT login in UserService\nMessages are historical observations; task state above is authoritative.\nNew mate messages:\n- (none)\nnextSinceSeq: 4",
                "Group state: ACTIVE\nA completed task means the assigned mate returned a report; it does not imply independent verification.\nMates:\n- 3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f: review backend session isolation\nTasks:\n- node-1 [COMPLETED] mate=3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f skillset=coding dependsOn=[]\nImplement JWT login in UserService\nMessages are historical observations; task state above is authoritative.\nNew mate messages:\n- seq=6 sender=3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f type=ACCEPT\ndispatch=node-1-dispatch-3 currentDispatch=true\npayload=node-1: login implemented; session isolation verified\nnextSinceSeq: 6",
                "Group state: ACTIVE\nA completed task means the assigned mate returned a report; it does not imply independent verification.\nMates:\n- 3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f: review backend session isolation\nTasks:\n- node-1 [RUNNING] mate=3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f skillset=coding dependsOn=[]\nImplement JWT login in UserService\nMessages are historical observations; task state above is authoritative.\nNew mate messages:\n- seq=5 sender=3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f type=FEEDBACK\ndispatch=(uncorrelated) currentDispatch=false\npayload=node-1:feedback:test failed\nnextSinceSeq: 5"
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
            try {
                capability.awaitChange(since, initial.state(), waitSeconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolErrors.failure("Group not inspected: wait interrupted.");
            }
            var inspection = capability.inspect(since);
            if (inspection == null)
                return ToolErrors.failure("Group not inspected: group record disappeared.");
            return render(inspection.group(), inspection.messages(), since);
        }
    }

    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description = "Record a Leader note in the group's Blackboard.",
            behavior =
                    "Records a note for the Leader. Does not deliver instructions to Mates or execute work.",
            whenToUse =
                    "Record a short status, feedback, artifact reference or log reference for your own coordination.",
            whenNotToUse =
                    "For Mate work, use create_task with mateId. TASK_DISPATCH and ACCEPT are reserved for task execution and completion.",
            resultContract = "On success: posted. Otherwise: Not posted followed by the reason.",
            errorsAndEdgeCases =
                    "Only receiver LEADER is accepted. Disbanded groups, blank payloads and payloads over 4096 characters are rejected.",
            security = "Notes cannot create tasks or mark work completed.",
            examples = {
                "{\"type\":\"STATUS\",\"payload\":\"Review the failed node before scheduling replacement work.\"}",
                "{\"type\":\"ARTIFACT_REF\",\"payload\":\"Draft report: reports/drift-analysis.md\"}",
                "{\"type\":\"LOG_REF\",\"payload\":\"Failed node output: logs/build-2026-09-19.log\"}",
                "{\"type\":\"FEEDBACK\",\"receiver\":\"LEADER\",\"payload\":\"The retry fix addressed the race; re-run the soak test before closing.\"}"
            },
            returnExamples = {"posted", "posted", "posted", "posted"})
    @Component
    public static final class PostMessage implements GroupControlTool<PostMessage.Args> {

        private final @NonNull GroupControlCapability capability;

        public PostMessage(@NonNull GroupControlCapability capability) {
            this.capability = capability;
        }

        public record Args(
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Note type: ARTIFACT_REF, LOG_REF, FEEDBACK or STATUS.")
                        BlackboardMessage.@NonNull MessageType type,
                @SecurityHint(ParamCategory.GENERIC)
                        @Doc("Omit or use LEADER; Mate work must use create_task.")
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
            if (!"LEADER".equals(receiver)
                    || args.type() == BlackboardMessage.MessageType.TASK_DISPATCH
                    || args.type() == BlackboardMessage.MessageType.ACCEPT) {
                return ToolErrors.failure(
                        "Not posted: use create_task with mateId for tracked Mate work. post_message only records Leader notes.");
            }
            if (group.state() == Group.GroupState.DISBANDED) {
                return ToolErrors.failure("Not posted: group is no longer active.");
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
        return PromptLibrary.text(
                "group-disband-brief", Map.of("group", GroupPromptInputs.snapshot(g)));
    }

    private static @NonNull String render(
            @NonNull GroupSnapshot group,
            @NonNull List<@NonNull BlackboardMessage> messages,
            long since) {
        List<Map<String, Object>> observations = new ArrayList<>();
        long next = since;
        for (BlackboardMessage message : messages) {
            next = Math.max(next, message.turnSeq());
            var report = GroupOrchestrator.resultFromMate(message);
            String payload =
                    report instanceof DagNode.ResultSuccess success
                            ? success.summary()
                            : message.payload();
            String dispatch = message.dispatchId();
            observations.add(
                    Map.of(
                            "sequence",
                            message.turnSeq(),
                            "sender",
                            message.senderId(),
                            "type",
                            message.type(),
                            "dispatch",
                            dispatch == null ? "" : dispatch,
                            "current",
                            dispatch != null
                                    && group.nodes().stream()
                                            .anyMatch(
                                                    node ->
                                                            Objects.equals(
                                                                    dispatch, node.dispatchId())),
                            "payload",
                            payload));
        }
        return PromptLibrary.text(
                "group-inspect",
                Map.of(
                        "group",
                        GroupPromptInputs.snapshot(group),
                        "messages",
                        observations,
                        "next",
                        next));
    }
}
