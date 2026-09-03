package top.focess.veto.agent.mcp.tools;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.Required;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolCapability;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolErrors;
import top.focess.veto.agent.mcp.ToolJson;
import top.focess.veto.agent.mcp.ToolResultFormat;
import top.focess.veto.agent.mcp.ToolSecurity;
import top.focess.veto.sandbox.BackgroundTaskManager;

/** Queues standard-input bytes to a background task owned by the calling agent. */
@Component
@ToolSecurity(
        risk = RiskCategory.AGENT,
        capability = ToolCapability.TASK_CONTROL,
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
                    "Encodes content as UTF-8, optionally appends one newline, queues it in order, and optionally closes stdin after those bytes. "
                            + "The call returns after queueing; later pipe failures are exposed by view_task.",
            whenToUse =
                    "Use it to answer an interactive prompt or send input to a process launched by run_task.",
            whenNotToUse =
                    "Do not use it for a finished task, a task owned by another agent, or to start a new process.",
            resultContract =
                    "Success returns JSON with `status`, `taskId`, `bytes`, `newline`, and `closeQueued`. Failures use TASK_NOT_FOUND, "
                            + "TASK_NOT_RUNNING, STDIN_CLOSED, EMPTY_INPUT, INPUT_TOO_LARGE, or INPUT_QUEUE_FULL.",
            errorsAndEdgeCases =
                    "Each call is limited to 64 KiB and each task to 256 KiB of queued input. Empty content is valid only when a newline is appended or stdin is closed.",
            security =
                    "Agent-scoped task control. The task id is resolved only inside the calling agent and input cannot grant new process authority.",
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
    public @NonNull String getDescription() {
        return "Queue text to the standard input of a running background task.";
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
            bytes = java.util.Arrays.copyOf(content, content.length + 1);
            bytes[content.length] = (byte) '\n';
        } else {
            bytes = content;
        }
        var context = ToolCallContextHolder.get();
        if (context == null) {
            throw new SecurityException("input_task requires an agent execution context");
        }
        BackgroundTaskManager.InputResult queued =
                taskManager.queueInput(context.agentId(), args.taskId(), bytes, args.closeStdin());
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
