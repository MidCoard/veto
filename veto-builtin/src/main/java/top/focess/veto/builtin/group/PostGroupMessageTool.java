package top.focess.veto.builtin.group;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.SecurityHint;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/** {@code post_message} - record a Leader note in the group's blackboard. */
@ToolDoc(
        resultFormats = {ToolResultFormat.PLAINTEXT},
        description =
                """
                Record a Leader note in the group's Blackboard.\
                """,
        behavior =
                """
                Records a note for the Leader. Does not deliver instructions to Mates or execute work.\
                """,
        whenToUse =
                """
                Record a short status, feedback, artifact reference or log reference for your own \
                coordination.\
                """,
        whenNotToUse =
                """
                For Mate work, use create_task with mateId. TASK_DISPATCH and ACCEPT are reserved for task \
                execution and completion.\
                """,
        resultContract =
                """
                On success: `posted`. On failure: `Not posted: <reason>` with code `NO_ACTIVE_GROUP` (no \
                active group), `NOT_ACTIVE` (disbanded group), or `INVALID_ARGUMENTS` (non-LEADER receiver, \
                reserved type, blank payload, or a payload over 4096 characters).\
                """,
        errorsAndEdgeCases =
                """
                Only receiver LEADER is accepted. Disbanded groups, blank payloads and payloads over 4096 \
                characters are rejected.\
                """,
        security =
                """
                Notes cannot create tasks or mark work completed.\
                """,
        examples = {
            "{\"type\":\"STATUS\",\"payload\":\"Review the failed node before scheduling replacement work.\"}",
            "{\"type\":\"ARTIFACT_REF\",\"payload\":\"Draft report: reports/drift-analysis.md\"}",
            "{\"type\":\"LOG_REF\",\"payload\":\"Failed node output: logs/build-2026-09-19.log\"}",
            "{\"type\":\"FEEDBACK\",\"receiver\":\"LEADER\",\"payload\":\"The retry fix addressed the race; re-run the soak test before closing.\"}",
            "{\"type\":\"STATUS\",\"receiver\":\"3f8a2c10-9b2e-4c1d-8e5f-2a6b7c8d9e0f\",\"payload\":\"Direct the billing review at the retry logic.\"}"
        },
        returnExamples = {
            "posted",
            "posted",
            "posted",
            "posted",
            "Not posted: use create_task with mateId for tracked Mate work. post_message only records Leader notes."
        })
public final class PostGroupMessageTool extends GroupControlTool<PostGroupMessageTool.Args> {

    private final GroupControlCapability capability;

    /** Declaration-only instance; the host supplies the capability at execution time. */
    public PostGroupMessageTool() {
        this.capability = null;
    }

    /** Creates an instance bound to the given host capability. */
    public PostGroupMessageTool(@NonNull GroupControlCapability capability) {
        this.capability = capability;
    }

    /** Model-facing arguments of {@code post_message}. */
    public record Args(
            @SecurityHint(ParamCategory.GENERIC)
                    @Doc("Note type: ARTIFACT_REF, LOG_REF, FEEDBACK or STATUS.")
                    BlackboardMessage.@NonNull MessageType type,
            @SecurityHint(ParamCategory.GENERIC)
                    @Doc("Omit or use LEADER; Mate work must use create_task.")
                    String receiver,
            @SecurityHint(ParamCategory.GENERIC)
                    @Doc(
                            "Non-blank payload up to 4096 characters. Prefer paths over file"
                                    + " contents.")
                    @NonNull String payload) {}

    @Override
    public @NonNull String getName() {
        return "post_message";
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
        GroupSnapshot group = capability.snapshot(getName());
        if (group == null) {
            return ToolErrors.failure(
                    ToolErrorCode.GROUP.NO_ACTIVE_GROUP,
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
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Not posted: use create_task with mateId for tracked Mate work."
                            + " post_message only records Leader notes.");
        }
        if (group.state() == GroupState.DISBANDED) {
            return ToolErrors.failure(
                    ToolErrorCode.GROUP.NOT_ACTIVE, "Not posted: group is no longer active.");
        }
        String payload = args.payload();
        if (payload.isBlank()) {
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Not posted: payload must not be blank.");
        }
        if (payload.length() > MAX_PAYLOAD_CHARS) {
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Not posted: payload exceeds 4096 characters.");
        }
        capability.post(receiver, args.type(), payload);
        return "posted";
    }

    private static final int MAX_PAYLOAD_CHARS = 4096;
}
