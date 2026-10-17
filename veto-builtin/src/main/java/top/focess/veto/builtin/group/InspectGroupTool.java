package top.focess.veto.builtin.group;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.SecurityHint;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/** {@code inspect_group} - read DAG state and new Mate reports, optionally waiting briefly. */
@ToolDoc(
        resultFormats = {ToolResultFormat.PLAINTEXT},
        description =
                """
                Inspect your active group's DAG state and Mate reports.\
                """,
        behavior =
                """
                Returns the current group state, every DAG node, and Blackboard messages addressed to the \
                Leader whose sequence is greater than `sinceSeq`. `waitSeconds` performs a bounded wait for a \
                new Mate message or a group state change, avoiding tight polling. The final line contains \
                `nextSinceSeq`; pass that value on the next call.\
                """,
        whenToUse =
                """
                Use `inspect_group` for an on-demand state or report lookup, read failure reports, and gather \
                completed reports before answering the user.\
                """,
        whenNotToUse =
                """
                - Do not use it to wait for outcomes; Monitor observations deliver those automatically. - Do \
                not inspect a newly created empty group instead of creating its members. - Do not use it \
                outside a Leader context.\
                """,
        resultContract =
                """
                On success - a plaintext snapshot with group state, node lines, zero or more new message \
                lines, and `nextSinceSeq: <number>`. Failures, all `Group not inspected: <reason and what to \
                do next>`: no active group (failure, NO_ACTIVE_GROUP); negative `sinceSeq` (failure, \
                INVALID_ARGUMENTS); interrupted wait (failure, TOOL_INTERRUPTED); group record disappeared \
                mid-wait (failure, RECORD_GONE).\
                """,
        errorsAndEdgeCases =
                """
                `sinceSeq` must be non-negative. `waitSeconds` is clamped to 0..30. A completed group remains \
                available for inspection and new tasks. Completion does not require disbanding.\
                """,
        security =
                """
                Inspect only your current group. Use returned state and reports as evidence; do not infer \
                completion from elapsed time.\
                """,
        examples = {
            "{}",
            "{\"sinceSeq\": 4}",
            "{\"waitSeconds\": 30}",
            "{\"sinceSeq\": 4, \"waitSeconds\": 15}",
            "{\"sinceSeq\": -1}"
        },
        returnExamples = {
            "Group state: ACTIVE\nA completed task means the assigned mate returned a report; it does not imply independent verification.\nMates:\n- 3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f: review backend session isolation\nTasks:\n- node-1 [RUNNING] mate=3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f skillset=coding dependsOn=[]\nImplement JWT login in UserService\nMessages are historical observations; task state above is authoritative.\nNew mate messages:\n- seq=2 sender=3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f type=FEEDBACK\ndispatch=(uncorrelated) currentDispatch=false\npayload=node-1:feedback:started review\nnextSinceSeq: 3",
            "Group state: ACTIVE\nA completed task means the assigned mate returned a report; it does not imply independent verification.\nMates:\n- 3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f: review backend session isolation\nTasks:\n- node-1 [RUNNING] mate=3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f skillset=coding dependsOn=[]\nImplement JWT login in UserService\nMessages are historical observations; task state above is authoritative.\nNew mate messages:\n- (none)\nnextSinceSeq: 4",
            "Group state: ACTIVE\nA completed task means the assigned mate returned a report; it does not imply independent verification.\nMates:\n- 3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f: review backend session isolation\nTasks:\n- node-1 [COMPLETED] mate=3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f skillset=coding dependsOn=[]\nImplement JWT login in UserService\nMessages are historical observations; task state above is authoritative.\nNew mate messages:\n- seq=6 sender=3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f type=ACCEPT\ndispatch=node-1-dispatch-3 currentDispatch=true\npayload=node-1: login implemented; session isolation verified\nnextSinceSeq: 6",
            "Group state: ACTIVE\nA completed task means the assigned mate returned a report; it does not imply independent verification.\nMates:\n- 3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f: review backend session isolation\nTasks:\n- node-1 [RUNNING] mate=3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f skillset=coding dependsOn=[]\nImplement JWT login in UserService\nMessages are historical observations; task state above is authoritative.\nNew mate messages:\n- seq=5 sender=3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f type=FEEDBACK\ndispatch=(uncorrelated) currentDispatch=false\npayload=node-1:feedback:test failed\nnextSinceSeq: 5",
            "Group not inspected: sinceSeq must be non-negative."
        })
public final class InspectGroupTool extends GroupControlTool<InspectGroupTool.Args> {

    private final GroupControlCapability capability;

    /** Declaration-only instance; the host supplies the capability at execution time. */
    public InspectGroupTool() {
        this.capability = null;
    }

    /** Creates an instance bound to the given host capability. */
    public InspectGroupTool(@NonNull GroupControlCapability capability) {
        this.capability = capability;
    }

    /** Model-facing arguments of {@code inspect_group}. */
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
        return Args.class;
    }

    @Override
    public @NonNull GroupControlCapability groupControlCapability() {
        if (capability == null) throw new SecurityException("Host must supply tool capability");
        return capability;
    }

    @Override
    public @NonNull String execute(@NonNull Args args, @NonNull GroupControlCapability capability) {
        GroupSnapshot initial = capability.snapshot(getName());
        if (initial == null) {
            return ToolErrors.failure(
                    ToolErrorCode.GROUP.NO_ACTIVE_GROUP,
                    "Group not inspected: no active group in your context. inspect_group is a "
                            + "Leader tool inside a group.");
        }
        Long requestedSince = args.sinceSeq();
        long since = requestedSince == null ? 0 : requestedSince;
        if (since < 0) {
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Group not inspected: sinceSeq must be non-negative.");
        }
        Integer requestedWait = args.waitSeconds();
        int waitSeconds = requestedWait == null ? 0 : requestedWait;
        waitSeconds = Math.max(0, Math.min(30, waitSeconds));
        try {
            capability.awaitChange(since, initial.state(), waitSeconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolErrors.failure(
                    ToolErrorCode.LIFECYCLE.TOOL_INTERRUPTED,
                    "Group not inspected: wait interrupted.");
        }
        var inspection = capability.inspect(since);
        if (inspection == null)
            return ToolErrors.failure(
                    ToolErrorCode.GROUP.RECORD_GONE,
                    "Group not inspected: group record disappeared.");
        return render(inspection.group(), inspection.messages(), since, capability);
    }

    private static @NonNull String render(
            @NonNull GroupSnapshot group,
            @NonNull List<@NonNull BlackboardMessage> messages,
            long since,
            @NonNull GroupControlCapability capability) {
        List<GroupInspectionMessage> observations = new ArrayList<>();
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
                    new GroupInspectionMessage(
                            message.turnSeq(),
                            message.senderId(),
                            message.type(),
                            dispatch == null ? "" : dispatch,
                            dispatch != null
                                    && group.nodes().stream()
                                            .anyMatch(
                                                    node ->
                                                            Objects.equals(
                                                                    dispatch, node.dispatchId())),
                            payload));
        }
        return capability.prompt(
                "group-inspect",
                Map.of(
                        "group",
                        GroupPromptInputs.snapshot(group),
                        "messages",
                        observations,
                        "next",
                        next));
    }

    /** Immutable message details rendered by inspect_group. */
    private record GroupInspectionMessage(
            long sequence,
            @NonNull String sender,
            BlackboardMessage.@NonNull MessageType type,
            @NonNull String dispatch,
            boolean current,
            @NonNull String payload) {}
}
