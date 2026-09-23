package top.focess.veto.builtin.tools;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.TaskControlCapability;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.TaskControlTool;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolJson;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolSecurity;

/**
 * {@code stop_task} - force-stop a background task launched by {@code run_task}. Idempotent:
 * stopping an already-exited task reports its final status without error.
 */
@ToolSecurity(capability = ToolCapability.TASK_CONTROL, defaultDanger = Danger.SAFE)
@ToolDoc(
        resultFormats = {ToolResultFormat.JSON},
        description =
                "Force-stop a background task launched by run_task. The only sanctioned way to stop a task; idempotent.",
        behavior =
                """
                Requests a force-stop of the task's direct process and waits up to five seconds \
                for its final status. Idempotent: stopping a task that already exited reports \
                `already_exited` and its final status without error. The task remains queryable \
                with `view_task`, including its final output.
                """,
        whenToUse =
                """
                Use `stop_task` to end a background task you launched with `run_task` - a dev \
                server you no longer need, a watcher you are done with, or a runaway process \
                before its `timeout` elapses. This is the ONLY sanctioned way to stop a \
                background task.
                """,
        whenNotToUse =
                """
                - Do not stop tasks with an OS kill command (`taskkill` / `kill`) - that \
                bypasses task tracking and requires separate approval. Use \
                `stop_task` instead.
                - Do not use it to run or inspect anything - it only stops (use `view_task` \
                to inspect).
                """,
        resultContract =
                """
                - Success: `status`, `taskId`, `alive`, and optional `exitCode`. `status` is \
                `stopped`, `stop_requested` when still alive after the wait, or `already_exited`.
                - Unknown task (failure, TASK_NOT_FOUND): \
                `Task not found: <taskId>`.
                """,
        errorsAndEdgeCases =
                """
                - If the process does not exit within the five-second wait, the returned status may still \
                be alive; inspect it with `view_task` before assuming termination completed.
                """,
        security =
                "You can stop only your own tasks. Prefer this tool over an operating-system kill command.",
        examples = {
            "{\"taskId\": \"bg-3\"}",
            "{\"taskId\": \"bg-7\"}",
            "{\"taskId\": \"bg-12\"}",
            "{\"taskId\": \"bg-99\"}"
        },
        returnExamples = {
            "{\"status\": \"stopped\", \"taskId\": \"bg-3\", \"alive\": false, \"exitCode\": 1}",
            "{\"status\": \"stop_requested\", \"taskId\": \"bg-7\", \"alive\": true}",
            "{\"status\": \"already_exited\", \"taskId\": \"bg-12\", \"alive\": false, \"exitCode\": 0}",
            "Task not found: bg-99"
        })
public final class StopTaskTool implements TaskControlTool<StopTaskTool.Args> {
    private final TaskControlCapability capability;

    public StopTaskTool() {
        this.capability = null;
    }

    public StopTaskTool(@NonNull TaskControlCapability capability) {
        this.capability = capability;
    }

    public record Args(@NonNull @Doc("The task id (from run_task).") String taskId) {}

    @Override
    public @NonNull String getName() {
        return "stop_task";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull TaskControlCapability taskControlCapability() {
        if (capability == null) throw new SecurityException("Host must supply tool capability");
        return capability;
    }

    @Override
    public @NonNull String execute(@NonNull Args args, @NonNull TaskControlCapability capability) {
        var before = capability.status(args.taskId());
        if (before.isEmpty())
            return ToolErrors.failure(
                    ToolErrorCode.TASK.TASK_NOT_FOUND, "Task not found: " + args.taskId());
        var stopped = capability.stop(args.taskId());
        if (stopped.isEmpty())
            return ToolErrors.failure(
                    ToolErrorCode.TASK.TASK_NOT_FOUND, "Task not found: " + args.taskId());
        var info = stopped.get();
        return ToolJson.object(
                new Result(
                        info.alive()
                                ? "stop_requested"
                                : before.get().alive() ? "stopped" : "already_exited",
                        info.taskId(),
                        info.alive(),
                        info.exitCode()));
    }

    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Result(
            @NonNull String status,
            @NonNull String taskId,
            boolean alive,
            @org.jspecify.annotations.Nullable Integer exitCode) {}
}
