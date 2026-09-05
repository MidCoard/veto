package top.focess.veto.agent.tool.builtin;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.TaskControlCapability;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.TaskControlTool;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.ToolSecurity;

/**
 * {@code view_task} - inspect background tasks launched by {@code run_task}. With a {@code taskId}
 * it returns that task's status (alive / exitCode / uptime) plus recent merged output; without a
 * {@code taskId} it lists every task the calling agent owns. Read-only.
 */
@Component
@ToolSecurity(capability = ToolCapability.TASK_CONTROL, defaultDanger = Danger.SAFE)
public final class ViewTaskTool implements TaskControlTool<ViewTaskTool.Args> {
    private final @NonNull TaskControlCapability capability;

    public ViewTaskTool(@NonNull TaskControlCapability capability) {
        this.capability = capability;
    }

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
                    Use `view_task` to check on a background task you launched with `run_task` - \
                    whether it is still alive, its exit code once it ends, and its recent output. \
                    Call it with a `taskId` for one task, or with no `taskId` to list every task you \
                    own. You are also told automatically when a task ends (and why - user stop, \
                    your own stop_task, timeout, or its own exit), so you rarely need to poll.
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
                    `inputFailures`.
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
    public record Args(
            @Doc("The task id (from run_task). Omit to list every task the calling agent owns.")
                    String taskId) {}

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
        return capability.viewTask(args);
    }
}
