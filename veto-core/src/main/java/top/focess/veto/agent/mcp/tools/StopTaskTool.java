package top.focess.veto.agent.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeTool;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolCapability;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolErrors;
import top.focess.veto.agent.mcp.ToolResultFormat;
import top.focess.veto.agent.mcp.ToolSecurity;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.sandbox.BackgroundTaskManager;

/**
 * {@code stop_task} - force-stop a background task launched by {@code run_task}. Idempotent:
 * stopping an already-exited task reports its final status without error.
 */
@Component
@ToolSecurity(risk = RiskCategory.AGENT, capability = ToolCapability.TASK_CONTROL)
public final class StopTaskTool implements NativeTool<StopTaskTool.Args> {

    private final @NonNull BackgroundTaskManager taskManager;
    private final @NonNull ObjectMapper mapper;

    public StopTaskTool(
            @NonNull BackgroundTaskManager taskManager,
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NonNull ObjectMapper mapper) {
        this.taskManager = taskManager;
        this.mapper = mapper;
    }

    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description =
                    "Force-stop a background task launched by run_task. The only sanctioned way to"
                            + " stop a task; idempotent.",
            usage =
                    """
                    #### When to use
                    Use `stop_task` to end a background task you launched with `run_task` - a dev \
                    server you no longer need, a watcher you are done with, or a runaway process \
                    before its `timeout` elapses. This is the ONLY sanctioned way to stop a \
                    background task.

                    #### When NOT to use
                    - Do not stop tasks with an OS kill command (`taskkill` / `kill`) - that \
                    bypasses the task registry, is classified DANGEROUS, and needs approval. Use \
                    `stop_task` instead.
                    - Do not use it to run or inspect anything - it only stops (use `view_task` \
                    to inspect).

                    #### Behavior
                    Stops the task's process and waits up to five seconds for its final status. It records the exit with \
                    the cause AGENT_STOP - the exit notice you receive later says you stopped it, \
                    so a stop never looks like a crash. Idempotent: stopping a task that already \
                    exited just reports its final status without error. The task stays in the \
                    registry (queryable via `view_task`) so you can still read its final output.

                    #### Return format
                    - Success: `status`, `taskId`, `alive`, and `exitCode` once the process exits.
                    - Unknown task (failure): \
                    `task not found: <taskId>`.

                    #### Errors & edge cases
                    - Unknown `taskId` -> `task not found: ...`.
                    - Already-exited task -> still returns `stopped` with its final exit code.

                    #### Security
                    Agent tool (`RiskCategory.AGENT`). Scoped to the calling agent - you can only \
                    stop your own tasks. Prefer this over any OS-level kill.
                    """,
            examples = {"{\"taskId\": \"bg-3\"}"},
            returnExamples = {
                "{\"status\": \"stopped\", \"taskId\": \"bg-3\", \"alive\": false, \"exitCode\": -1}"
            })
    public record Args(@NonNull @Doc("The task id (from run_task).") String taskId) {}

    @Override
    public @NonNull String getName() {
        return "stop_task";
    }

    @Override
    public @NonNull String getDescription() {
        return "Force-stop a background task launched by run_task. This is the ONLY sanctioned way"
                + " to stop a background task - never use OS kill commands (taskkill/kill) for it."
                + " Idempotent: stopping an already-exited task reports its final status.";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(@NonNull Args args) {
        String agentId = currentAgentId();
        Optional<BackgroundTaskManager.TaskInfo> info =
                taskManager.stop(
                        agentId, args.taskId(), BackgroundTaskManager.ExitCause.AGENT_STOP);
        if (info.isEmpty()) {
            return error("task not found: " + args.taskId());
        }
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("status", "stopped");
            envelope.put("taskId", info.get().taskId());
            envelope.put("alive", info.get().alive());
            Integer exitCode = info.get().exitCode();
            if (exitCode != null) {
                envelope.put("exitCode", exitCode);
            }
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            return error("stop_task failed: " + e.getMessage());
        }
    }

    private static @NonNull String currentAgentId() {
        var ctx = ToolCallContextHolder.get();
        return ctx != null ? ctx.agentId() : "standalone";
    }

    private static @NonNull String error(@NonNull String message) {
        return ToolErrors.failure(message);
    }
}
