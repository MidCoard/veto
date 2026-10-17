package top.focess.veto.builtin.group;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/** {@code cancel_group_task} - cancel one group task and confirm execution exit. */
@ToolDoc(
        resultFormats = {ToolResultFormat.PLAINTEXT},
        description =
                """
                Cancel one group task and confirm execution exit.\
                """,
        behavior =
                """
                Cancels queued work before dispatch or interrupts the exact running attempt. Keeps the \
                collaborator and history. Pending cancellation continues to occupy the Mate until execution \
                and the result waiter exit.\
                """,
        whenToUse =
                """
                Use when the user asks to stop assigned work.\
                """,
        whenNotToUse =
                """
                Not for removing a collaborator or stopping independent background processes.\
                """,
        resultContract =
                """
                Success means CANCELLED with execution exit confirmed. A pending response means \
                CANCEL_REQUESTED; inspect_group or retry can confirm later. Dependents remain blocked, other \
                work can continue. Failure (failure, REQUEST_REJECTED): `Task not cancelled: <reason>`.\
                """,
        errorsAndEdgeCases =
                """
                Unknown or already successful tasks fail (failure, REQUEST_REJECTED). Cancelled tasks are \
                idempotent. Cancellation does not undo completed side effects or satisfy dependencies.\
                """,
        security =
                """
                Caller must lead the current userId and Session scoped group.\
                """,
        examples = {
            "{\"taskId\":\"analysis\"}",
            "{\"taskId\":\"legacy-import\"}",
            "{\"taskId\":\"soak-test\"}",
            "{\"taskId\":\"unknown-task\"}"
        },
        returnExamples = {
            "Task cancelled; execution exit confirmed. Dependent tasks remain blocked; explicitly replan or cancel them. Mate identity and history retained. Independent background processes are not stopped.",
            "Task cancelled; execution exit confirmed. Dependent tasks remain blocked; explicitly replan or cancel them. Mate identity and history retained. Independent background processes are not stopped.",
            "Task cancelled; execution exit confirmed. Dependent tasks remain blocked; explicitly replan or cancel them. Mate identity and history retained. Independent background processes are not stopped.",
            "Task not cancelled: Unknown task: unknown-task"
        })
public final class CancelGroupTaskTool extends GroupControlTool<CancelGroupTaskTool.Args> {
    private final GroupControlCapability capability;

    /** Declaration-only instance; the host supplies the capability at execution time. */
    public CancelGroupTaskTool() {
        this.capability = null;
    }

    /** Creates an instance bound to the given host capability. */
    public CancelGroupTaskTool(@NonNull GroupControlCapability capability) {
        this.capability = capability;
    }

    /** Model-facing arguments of {@code cancel_group_task}. */
    public record Args(@Doc("Existing task id.") @NonNull String taskId) {}

    @Override
    public @NonNull String getName() {
        return "cancel_group_task";
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
        var result = capability.cancelTask(args.taskId());
        if (result instanceof NodeEdit.Rejected rejected)
            return ToolErrors.failure(
                    ToolErrorCode.GROUP.REQUEST_REJECTED,
                    "Task not cancelled: " + rejected.reason());
        return "Task cancelled; execution exit confirmed. Dependent tasks remain blocked;"
                + " explicitly replan or cancel them. Mate identity and history retained."
                + " Independent background processes are not stopped.";
    }
}
