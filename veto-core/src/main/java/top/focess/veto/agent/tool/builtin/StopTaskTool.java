package top.focess.veto.agent.tool.builtin;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
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

/**
 * {@code stop_task} - force-stop a background task launched by {@code run_task}. Idempotent:
 * stopping an already-exited task reports its final status without error.
 */
@Component
@ToolSecurity(capability = ToolCapability.TASK_CONTROL, defaultDanger = Danger.SAFE)
public final class StopTaskTool implements TaskControlTool<StopTaskTool.Args> {
    private final @NonNull TaskControlCapability capability;

    public StopTaskTool(@NonNull TaskControlCapability capability) {
        this.capability = capability;
    }

    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description =
                    "Force-stop a background task launched by run_task. The only sanctioned way to"
                            + " stop a task; idempotent.",
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
                    - Unknown task (failure): \
                    `task not found: <taskId>`.
                    """,
            errorsAndEdgeCases =
                    """
                    - If the process does not exit within the five-second wait, the returned status may still \
                    be alive; inspect it with `view_task` before assuming termination completed.
                    """,
            security =
                    "You can stop only your own tasks. Prefer this tool over an operating-system kill command.",
            examples = {"{\"taskId\": \"bg-3\"}"},
            returnExamples = {
                "{\"status\": \"stopped\", \"taskId\": \"bg-3\", \"alive\": false, \"exitCode\": 1}"
            })
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
        return capability;
    }

    @Override
    public @NonNull String execute(@NonNull Args args, @NonNull TaskControlCapability capability) {
        var before = capability.status(args.taskId());
        if (before.isEmpty()) return ToolErrors.failure("task not found: " + args.taskId());
        var stopped = capability.stop(args.taskId());
        if (stopped.isEmpty()) return ToolErrors.failure("task not found: " + args.taskId());
        var info = stopped.get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
                "status",
                info.alive()
                        ? "stop_requested"
                        : before.get().alive() ? "stopped" : "already_exited");
        result.put("taskId", info.taskId());
        result.put("alive", info.alive());
        Integer exitCode = info.exitCode();
        if (exitCode != null) result.put("exitCode", exitCode);
        return ToolJson.object(result);
    }
}
