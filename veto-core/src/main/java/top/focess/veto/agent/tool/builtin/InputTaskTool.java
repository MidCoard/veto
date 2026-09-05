package top.focess.veto.agent.tool.builtin;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.TaskControlCapability;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.ParamCategory;
import top.focess.veto.agent.tool.Required;
import top.focess.veto.agent.tool.SecurityHint;
import top.focess.veto.agent.tool.TaskControlTool;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.ToolSecurity;

/** Queues standard-input bytes to a background task owned by the calling agent. */
@Component
@ToolSecurity(
        capability = ToolCapability.TASK_CONTROL,
        defaultDanger = Danger.SAFE,
        requiresSemanticScreening = true)
public final class InputTaskTool implements TaskControlTool<InputTaskTool.Args> {
    private final @NonNull TaskControlCapability capability;

    public InputTaskTool(@NonNull TaskControlCapability capability) {
        this.capability = capability;
    }

    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description = "Queue text to the standard input of a running background task.",
            behavior =
                    "Encodes content as UTF-8, optionally appends one newline, queues it in order,"
                            + " and optionally closes stdin after those bytes. The byte count includes"
                            + " the optional `\n"
                            + "`. A queued result means accepted by the bounded input queue, not yet"
                            + " consumed by the process; later pipe failures appear in view_task"
                            + " `inputFailures`.",
            whenToUse =
                    "Use it to answer an interactive prompt or send input to a process launched by"
                            + " run_task.",
            whenNotToUse =
                    "Do not use it for a finished task, a task from another session or agent, or to"
                            + " start a new process. Do not send credentials unless the user explicitly"
                            + " supplied and authorized them for this process.",
            resultContract =
                    "Success returns JSON with `status`, `taskId`, `bytes`, `newline`, and"
                            + " `closeQueued`. `bytes` is the queued UTF-8 byte count including an"
                            + " appended newline. In detailed-result mode, failures use TASK_NOT_FOUND,"
                            + " TASK_NOT_RUNNING, STDIN_CLOSED, EMPTY_INPUT, INPUT_TOO_LARGE, or"
                            + " INPUT_QUEUE_FULL; failure content is actionable plaintext in every"
                            + " mode.",
            errorsAndEdgeCases =
                    "Each call is limited to 64 KiB and each task to 256 KiB of queued input. Empty"
                            + " content is valid only when a newline is appended or stdin is closed."
                            + " Use view_task to inspect bounded asynchronous inputFailures and"
                            + " stop_task if the process must be terminated.",
            security =
                    "You can send input only to your own task in this session. Input does not grant the process additional file or network access and may require approval.",
            examples = {
                "{\"taskId\":\"bg-3\",\"content\":\"yes\",\"appendNewline\":true,\"closeStdin\":false}",
                "{\"taskId\":\"bg-3\",\"content\":\"\",\"appendNewline\":false,\"closeStdin\":true}"
            },
            returnExamples = {
                "{\"status\":\"queued\",\"taskId\":\"bg-3\",\"bytes\":4,\"newline\":true,\"closeQueued\":false}"
            })
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
        return capability.inputTask(args);
    }
}
