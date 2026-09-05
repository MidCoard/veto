package top.focess.veto.agent.tool.builtin;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.NativeTool;
import top.focess.veto.agent.tool.ParamCategory;
import top.focess.veto.agent.tool.Required;
import top.focess.veto.agent.tool.SecurityHint;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolJson;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.ToolSecurity;
import top.focess.veto.sandbox.BackgroundTaskManager;

/** Queues standard-input bytes to a background task owned by the calling agent. */
@Component
@ToolSecurity(
        capability = ToolCapability.TASK_CONTROL,
        defaultDanger = Danger.SAFE,
        requiresSemanticScreening = true)
public final class InputTaskTool implements NativeTool<InputTaskTool.Args> {

    private final @NonNull BackgroundTaskManager taskManager;

    public InputTaskTool(@NonNull BackgroundTaskManager taskManager) {
        this.taskManager = taskManager;
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
                    "Session- and agent-scoped task control. Gateway semantic screening receives"
                            + " the exact process executable, argv, cwd, and network policy; execution"
                            + " is rebound to that task instance after approval. Input cannot expand"
                            + " the process's existing sandbox authority.",
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
    public @NonNull String execute(@NonNull Args args) {
        if (args.content().isEmpty() && !args.appendNewline() && !args.closeStdin()) {
            return ToolErrors.failure(
                    "EMPTY_INPUT", "No input, newline, or stdin close was requested.");
        }
        byte[] content = args.content().getBytes(StandardCharsets.UTF_8);
        byte[] bytes;
        if (args.appendNewline()) {
            bytes = Arrays.copyOf(content, content.length + 1);
            bytes[content.length] = (byte) '\n';
        } else {
            bytes = content;
        }
        var context = ToolCallContextHolder.get();
        if (context == null) {
            throw new SecurityException("input_task requires an agent execution context");
        }
        UUID sessionId = context.sessionId();
        var binding = context.executionPermit().taskBinding();
        if (binding == null
                || sessionId == null
                || !binding.taskId().equals(args.taskId())
                || !binding.agentId().equals(context.agentId())
                || !binding.sessionId().equals(sessionId)) {
            throw new SecurityException(
                    "input_task requires the exact task instance screened by the Gateway");
        }
        BackgroundTaskManager.InputResult queued =
                taskManager.queueInput(
                        context.agentId(),
                        sessionId,
                        args.taskId(),
                        binding.taskInstanceId(),
                        bytes,
                        args.closeStdin());
        if (!queued.queued()) {
            String code = queued.status().name();
            String message =
                    switch (queued.status()) {
                        case TASK_NOT_FOUND -> "Task not found: " + args.taskId();
                        case TASK_NOT_RUNNING -> "Task is not running: " + args.taskId();
                        case STDIN_CLOSED -> "Task stdin is already closed: " + args.taskId();
                        case INPUT_TOO_LARGE -> "Input exceeds 65536 bytes.";
                        case INPUT_QUEUE_FULL -> "Task input queue exceeds 262144 bytes.";
                        case QUEUED ->
                                throw new IllegalStateException("queued result handled above");
                    };
            return ToolErrors.failure(code, message);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "queued");
        result.put("taskId", args.taskId());
        result.put("bytes", queued.bytes());
        result.put("newline", args.appendNewline());
        result.put("closeQueued", queued.closeQueued());
        return ToolJson.object(result);
    }
}
