package top.focess.veto.agent.tool.builtin;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.TaskControlCapability;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.TaskControlTool;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolJson;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.ToolSecurity;
import top.focess.veto.sandbox.BackgroundTaskManager;

/**
 * {@code view_task} - inspect background tasks launched by {@code run_task}. With a {@code taskId}
 * it returns that task's status (alive / exitCode / uptime) plus recent merged output; without a
 * {@code taskId} it lists every task the calling agent owns. Read-only.
 */
@Component
@ToolSecurity(capability = ToolCapability.TASK_CONTROL, defaultDanger = Danger.SAFE)
@ToolDoc(
        resultFormats = {ToolResultFormat.JSON},
        description =
                "Inspect a background task launched by run_task (status + recent output), or"
                        + " list every task you own when taskId is omitted.",
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
                - Unknown task (failure): \
                `task not found: <taskId>`.
                """,
        errorsAndEdgeCases =
                """
                - A task that already exited stays queryable (its final status + output).
                - At most the latest 5000 lines are retained, and an unterminated line is capped \
                at 65536 bytes; older or excess output cannot be recovered through this tool.
                """,
        security = "Read-only. You can view only your own tasks.",
        examples = {"{\"taskId\": \"bg-3\"}", "{}"},
        returnExamples = {
            "{\"taskId\": \"bg-3\", \"alive\": true, \"pid\": 12345, \"startedAt\":"
                    + " \"2026-01-01T00:00:00Z\", \"uptimeSeconds\": 42, \"command\": \"npm run"
                    + " dev\", \"cwd\": \"/abs/app\", \"recentOutput\": \"VITE ready in 300 ms\"}",
            "{\"count\": 1, \"tasks\": [{\"taskId\": \"bg-3\", \"command\": \"npm run dev\","
                    + " \"alive\": true}]}"
        })
public final class ViewTaskTool implements TaskControlTool<ViewTaskTool.Args> {
    private final @NonNull TaskControlCapability capability;

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
    public @NonNull TaskControlCapability taskControlCapability() {
        return capability;
    }

    @Override
    public @NonNull String execute(@NonNull Args args, @NonNull TaskControlCapability capability) {
        String taskId = args.taskId();
        if (taskId == null || taskId.isBlank()) {
            if (Boolean.TRUE.equals(args.waitForExit()))
                return ToolErrors.failure("waitForExit requires taskId");
            var all = capability.list();
            return ToolJson.object(
                    new TaskList(all.size(), all.stream().map(ViewTaskTool::summary).toList()));
        } else {
            var found =
                    Boolean.TRUE.equals(args.waitForExit())
                            ? awaitExit(capability, taskId)
                            : capability.status(taskId);
            if (found.isEmpty()) return ToolErrors.failure("task not found: " + taskId);
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
            @NonNull String taskId,
            @NonNull String command,
            boolean alive,
            @Nullable Integer exitCode) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskDetail(
            @NonNull String taskId,
            @NonNull String command,
            boolean alive,
            @Nullable Integer exitCode,
            long pid,
            @NonNull String startedAt,
            long uptimeSeconds,
            @NonNull String cwd,
            @NonNull String recentOutput,
            @NonNull String outputCapture,
            @NonNull List<String> inputFailures) {}

    private static @NonNull Optional<BackgroundTaskManager.TaskInfo> awaitExit(
            @NonNull TaskControlCapability capability, @NonNull String taskId) {
        try {
            return capability.awaitExit(taskId);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Task result wait interrupted");
        }
    }

    private static @NonNull TaskSummary summary(BackgroundTaskManager.@NonNull TaskInfo task) {
        return new TaskSummary(task.taskId(), task.command(), task.alive(), task.exitCode());
    }
}
