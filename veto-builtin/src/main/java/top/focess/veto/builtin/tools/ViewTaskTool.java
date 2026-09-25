package top.focess.veto.builtin.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolJson;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolSecurity;
import top.focess.veto.builtin.process.TaskControlCapability;
import top.focess.veto.builtin.process.TaskInfo;

/**
 * {@code view_task} - inspect background tasks launched by {@code run_task}. With a {@code taskId}
 * it returns that task's status (alive / exitCode / uptime) plus recent merged output; without a
 * {@code taskId} it lists every task the calling agent owns. Read-only.
 */
@ToolSecurity(capability = ToolCapability.PLUGIN_LOCAL, defaultDanger = Danger.SAFE)
@ToolDoc(
        resultFormats = {ToolResultFormat.JSON},
        description =
                "Inspect a background task launched by run_task (status + recent output), or list every task you own when taskId is omitted.",
        behavior =
                """
                With `taskId`: returns that task's status (alive / exitCode / pid / uptime / \
                command / cwd), the last 50 lines of its merged stdout+stderr, and up to 20 \
                recent asynchronous stdin write diagnostics in `inputFailures`. \
                Without `taskId`: returns a short list of every task you own (taskId, command, \
                alive, exitCode). Read-only - it never changes a task.
                """,
        whenToUse =
                """
                Set `waitForExit=true` with `taskId` when the assigned task needs the final result: \
                one cancellable call waits for exit and drained output, keeping the assignment open. \
                Leave it false for an immediate progress check or long-lived server inspection. \
                Without `taskId`, list your tasks. After an exit notification, read the result once \
                if needed; the notification does not contain output. Do not poll to pass time.
                """,
        whenNotToUse =
                """
                - Do not use it for commands whose result you need inline - that is `run_command`.
                - Do not poll it in a tight loop; the task's end is pushed to you on your next turn.
                """,
        resultContract =
                """
                - Single-task success: `taskId`, `alive`, optional `exitCode`, \
                `pid`, `startedAt`, `uptimeSeconds`, `command`, `cwd`, `recentOutput`, and \
                `outputCapture` (merged-stream limitation), and `inputFailures`.
                - List success: `count` and `tasks`; each task contains only `taskId`, \
                `command`, `alive`, and optional `exitCode`.
                - Unknown task (failure, TASK_NOT_FOUND): \
                `Task not found: <taskId>`.
                - `waitForExit` without `taskId` (failure, INVALID_ARGUMENTS): \
                `Invalid arguments: waitForExit requires taskId.`
                """,
        errorsAndEdgeCases =
                """
                - A task that already exited stays queryable (its final status + output).
                - At most the latest 5000 lines are retained, and an unterminated line is capped \
                at 65536 bytes; older or excess output cannot be recovered through this tool.
                """,
        security = "You can view only your own tasks.",
        examples = {
            "{}",
            "{\"taskId\": \"bg-3\"}",
            "{\"taskId\": \"bg-3\", \"waitForExit\": true}",
            "{\"taskId\": \"bg-99\"}"
        },
        returnExamples = {
            "{\"count\": 1, \"tasks\": [{\"taskId\": \"bg-3\", \"command\": \"npm run dev\", \"alive\": true}]}",
            "{\"taskId\": \"bg-3\", \"command\": \"npm run dev\", \"alive\": true, \"pid\": 12345, \"startedAt\": \"2026-01-01T00:00:00Z\", \"uptimeSeconds\": 42, \"cwd\": \"/abs/project\", \"recentOutput\": \"VITE ready in 300 ms\", \"outputCapture\": \"recentOutput merges stdout and stderr without stream labels. Report it as combined output; it cannot establish that either stream was empty.\", \"inputFailures\": []}",
            "{\"taskId\": \"bg-3\", \"command\": \"npm run dev\", \"alive\": false, \"exitCode\": 0, \"pid\": 12345, \"startedAt\": \"2026-01-01T00:00:00Z\", \"uptimeSeconds\": 184, \"cwd\": \"/abs/project\", \"recentOutput\": \"Server stopped.\", \"outputCapture\": \"recentOutput merges stdout and stderr without stream labels. Report it as combined output; it cannot establish that either stream was empty.\", \"inputFailures\": []}",
            "Task not found: bg-99"
        })
public final class ViewTaskTool implements NativeTool<ViewTaskTool.Args> {
    private final TaskControlCapability capability;

    public ViewTaskTool() {
        this.capability = null;
    }

    public ViewTaskTool(@NonNull TaskControlCapability capability) {
        this.capability = capability;
    }

    public record Args(
            @Doc("The task id (from run_task). Omit to list every task the calling agent owns.")
                    String taskId,
            @Doc(
                            "Wait for exit and drained output. Requires taskId. Default false returns immediately.")
                    Boolean waitForExit) {
        public Args(String taskId) {
            this(taskId, false);
        }
    }

    @Override
    public @NonNull String getName() {
        return "view_task";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(@NonNull Args args) {
        return execute(args, taskControlCapability());
    }

    public @NonNull TaskControlCapability taskControlCapability() {
        if (capability == null) throw new SecurityException("Host must supply tool capability");
        return capability;
    }

    public @NonNull String execute(@NonNull Args args, @NonNull TaskControlCapability capability) {
        String taskId = args.taskId();
        if (taskId == null || taskId.isBlank()) {
            if (Boolean.TRUE.equals(args.waitForExit()))
                return ToolErrors.failure(
                        ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                        "Invalid arguments: waitForExit requires taskId.");
            var all = capability.list();
            return ToolJson.object(
                    new TaskList(all.size(), all.stream().map(ViewTaskTool::summary).toList()));
        } else {
            var found =
                    Boolean.TRUE.equals(args.waitForExit())
                            ? awaitExit(capability, taskId)
                            : capability.status(taskId);
            if (found.isEmpty())
                return ToolErrors.failure(
                        ToolErrorCode.TASK.TASK_NOT_FOUND, "Task not found: " + taskId);
            var task = found.get();
            return ToolJson.object(
                    new TaskDetail(
                            task.taskId(),
                            task.command(),
                            task.alive(),
                            task.exitCode(),
                            task.pid(),
                            task.startedAt().toString(),
                            task.uptimeSeconds(),
                            task.cwd(),
                            capability.output(taskId, 50).orElse(""),
                            "recentOutput merges stdout and stderr without stream labels. Report it as combined output;"
                                    + " it cannot establish that either stream was empty.",
                            capability.inputFailures(taskId)));
        }
    }

    public record TaskList(int count, @NonNull List<TaskSummary> tasks) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskSummary(
            @NonNull String taskId, @NonNull String command, boolean alive, Integer exitCode) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskDetail(
            @NonNull String taskId,
            @NonNull String command,
            boolean alive,
            Integer exitCode,
            long pid,
            @NonNull String startedAt,
            long uptimeSeconds,
            @NonNull String cwd,
            @NonNull String recentOutput,
            @NonNull String outputCapture,
            @NonNull List<String> inputFailures) {}

    private static @NonNull Optional<TaskInfo> awaitExit(
            @NonNull TaskControlCapability capability, @NonNull String taskId) {
        try {
            return capability.awaitExit(taskId);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Task result wait interrupted");
        }
    }

    private static @NonNull TaskSummary summary(@NonNull TaskInfo task) {
        return new TaskSummary(task.taskId(), task.command(), task.alive(), task.exitCode());
    }
}
