package top.focess.veto.agent.tool.builtin;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.TaskControlCapability;
import top.focess.veto.agent.tool.TaskControlTool;
import top.focess.veto.agent.tool.ToolErrorCode;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolJson;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.Required;
import top.focess.veto.api.agent.tool.SecurityHint;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolSecurity;

/** Queues standard-input bytes to a background task owned by the calling agent. */
@Component
@ToolSecurity(
        capability = ToolCapability.TASK_CONTROL,
        defaultDanger = Danger.SAFE,
        requiresSemanticScreening = true)
@ToolDoc(
        resultFormats = {ToolResultFormat.JSON},
        description = "Queue text to the standard input of a running background task.",
        behavior =
                """
                Encodes content as UTF-8, optionally appends one newline, queues it in order, and \
                optionally closes stdin after those bytes. The byte count includes the optional `\\n`. \
                A queued result means accepted by the bounded input queue, not yet consumed by the \
                process; later pipe failures appear in view_task `inputFailures`.""",
        whenToUse =
                "Use it to answer an interactive prompt or send input to a process launched by run_task.",
        whenNotToUse =
                """
                Do not use it for a finished task, a task from another session or agent, or to start \
                a new process. Do not send credentials unless the user explicitly supplied and \
                authorized them for this process.""",
        resultContract =
                """
                Success returns JSON with `status`, `taskId`, `bytes`, `newline`, and `closeQueued`. \
                `bytes` is the queued UTF-8 byte count including an appended newline. In \
                detailed-result mode, failures use TASK_NOT_FOUND (`Task not found: <taskId>`), \
                TASK_NOT_RUNNING (`Task not running: <taskId>`), STDIN_CLOSED \
                (`Stdin closed: <taskId>`), EMPTY_INPUT \
                (`Empty input: no content, newline, or stdin close was requested.`), FILE_TOO_LARGE \
                (`Input too large: content exceeds 65536 bytes.`), or INPUT_QUEUE_FULL \
                (`Input queue full: the task input queue exceeds 262144 bytes.`); failure content is \
                actionable plaintext in every mode.""",
        errorsAndEdgeCases =
                """
                Each call is limited to 64 KiB and each task to 256 KiB of queued input. Empty \
                content is valid only when a newline is appended or stdin is closed. Use view_task \
                to inspect bounded asynchronous inputFailures and stop_task if the process must be \
                terminated.""",
        security =
                "You can send input only to your own task in this session. Queued content is delivered to the process stdin verbatim.",
        examples = {
            "{\"taskId\":\"bg-3\",\"content\":\"yes\",\"appendNewline\":true,\"closeStdin\":false}",
            "{\"taskId\":\"bg-3\",\"content\":\"partial input\",\"appendNewline\":false,\"closeStdin\":false}",
            "{\"taskId\":\"bg-3\",\"content\":\"quit\",\"appendNewline\":true,\"closeStdin\":true}",
            "{\"taskId\":\"bg-3\",\"content\":\"\",\"appendNewline\":false,\"closeStdin\":true}",
            "{\"taskId\":\"bg-7\",\"content\":\"yes\",\"appendNewline\":true,\"closeStdin\":false}"
        },
        returnExamples = {
            "{\"status\":\"queued\",\"taskId\":\"bg-3\",\"bytes\":4,\"newline\":true,\"closeQueued\":false}",
            "{\"status\":\"queued\",\"taskId\":\"bg-3\",\"bytes\":13,\"newline\":false,\"closeQueued\":false}",
            "{\"status\":\"queued\",\"taskId\":\"bg-3\",\"bytes\":5,\"newline\":true,\"closeQueued\":true}",
            "{\"status\":\"queued\",\"taskId\":\"bg-3\",\"bytes\":0,\"newline\":false,\"closeQueued\":true}",
            "Task not running: bg-7"
        })
public final class InputTaskTool implements TaskControlTool<InputTaskTool.Args> {
    private final @NonNull TaskControlCapability capability;

    public InputTaskTool(@NonNull TaskControlCapability capability) {
        this.capability = capability;
    }

    public record Args(
            @NonNull @Doc("Task id returned by run_task.") String taskId,
            @NonNull @SecurityHint(ParamCategory.PROCESS_INPUT) @Doc("UTF-8 text to queue.")
                    String content,
            @Required @Doc("Append one platform-independent newline byte (`\\n`).")
                    boolean appendNewline,
            @Required @Doc("Close task stdin after this queued input is written.")
                    boolean closeStdin) {}

    @Override
    public @NonNull String getName() {
        return "input_task";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull TaskControlCapability taskControlCapability() {
        return capability;
    }

    @Override
    public @NonNull String execute(@NonNull Args args, @NonNull TaskControlCapability capability) {
        if (args.content().isEmpty() && !args.appendNewline() && !args.closeStdin()) {
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.EMPTY_INPUT,
                    "Empty input: no content, newline, or stdin close was requested.");
        }
        byte[] content = args.content().getBytes(StandardCharsets.UTF_8);
        byte[] bytes = args.appendNewline() ? Arrays.copyOf(content, content.length + 1) : content;
        if (args.appendNewline()) bytes[content.length] = (byte) '\n';
        var queued = capability.queueInput(args.taskId(), bytes, args.closeStdin());
        if (!queued.queued()) {
            String message =
                    switch (queued.status()) {
                        case TASK_NOT_FOUND -> "Task not found: " + args.taskId();
                        case TASK_NOT_RUNNING -> "Task not running: " + args.taskId();
                        case STDIN_CLOSED -> "Stdin closed: " + args.taskId();
                        case INPUT_TOO_LARGE -> "Input too large: content exceeds 65536 bytes.";
                        case INPUT_QUEUE_FULL ->
                                "Input queue full: the task input queue exceeds 262144 bytes.";
                        case QUEUED ->
                                throw new IllegalStateException("queued result handled above");
                    };
            ToolErrorCode code =
                    switch (queued.status()) {
                        case TASK_NOT_FOUND -> ToolErrorCode.TASK.TASK_NOT_FOUND;
                        case TASK_NOT_RUNNING -> ToolErrorCode.TASK.TASK_NOT_RUNNING;
                        case STDIN_CLOSED -> ToolErrorCode.TASK.STDIN_CLOSED;
                        case INPUT_TOO_LARGE -> ToolErrorCode.WORKSPACE.FILE_TOO_LARGE;
                        case INPUT_QUEUE_FULL -> ToolErrorCode.TASK.INPUT_QUEUE_FULL;
                        case QUEUED ->
                                throw new IllegalStateException("queued result handled above");
                    };
            return ToolErrors.failure(code, message);
        }
        return ToolJson.object(
                new Result(
                        "queued",
                        args.taskId(),
                        queued.bytes(),
                        args.appendNewline(),
                        queued.closeQueued()));
    }

    public record Result(
            @NonNull String status,
            @NonNull String taskId,
            int bytes,
            boolean newline,
            boolean closeQueued) {}
}
