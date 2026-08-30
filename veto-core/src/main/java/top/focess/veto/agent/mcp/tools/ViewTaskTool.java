package top.focess.veto.agent.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
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
import top.focess.veto.agent.mcp.ToolSecurity;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.sandbox.BackgroundTaskManager;

/**
 * {@code view_task} - inspect background tasks launched by {@code run_task}. With a {@code taskId}
 * it returns that task's status (alive / exitCode / uptime) plus the last {@code lines} of merged
 * output; without a {@code taskId} it lists every task the calling agent owns. Read-only.
 */
@Component
@ToolSecurity(risk = RiskCategory.AGENT, capability = ToolCapability.TASK_CONTROL)
public final class ViewTaskTool implements NativeTool<ViewTaskTool.Args> {

    private final @NonNull BackgroundTaskManager taskManager;
    private final @NonNull ObjectMapper mapper;

    public ViewTaskTool(
            @NonNull BackgroundTaskManager taskManager,
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NonNull ObjectMapper mapper) {
        this.taskManager = taskManager;
        this.mapper = mapper;
    }

    @ToolDoc(
            description =
                    "Inspect a background task launched by run_task (status + recent output), or"
                            + " list every task you own when taskId is omitted.",
            usage =
                    """
                    #### When to use
                    Use `view_task` to check on a background task you launched with `run_task` - \
                    whether it is still alive, its exit code once it ends, and its recent output. \
                    Call it with a `taskId` for one task, or with no `taskId` to list every task you \
                    own. You are also told automatically when a task ends (and why - user stop, \
                    your own stop_task, timeout, or its own exit), so you rarely need to poll.

                    #### When NOT to use
                    - Do not use it for commands whose result you need inline - that is `run_command`.
                    - Do not poll it in a tight loop; the task's end is pushed to you on your next turn.

                    #### Behavior
                    With `taskId`: returns that task's status (alive / exitCode / pid / uptime / \
                    command / cwd) plus the last `lines` of its merged stdout+stderr (default 50). \
                    Without `taskId`: returns a short list of every task you own (taskId, command, \
                    alive, exitCode). Read-only - it never changes a task.

                    #### Return format
                    Single task - JSON with `taskId`, `alive`, `exitCode`, `pid`, `startedAt`, \
                    `uptimeSeconds`, `command`, `cwd`, `recentOutput`. List - JSON with `count` and \
                    `tasks`. Unknown `taskId` -> error envelope.

                    #### Errors & edge cases
                    - Unknown `taskId` -> `{"status":"error","error":"task not found: ..."}`.
                    - `lines` <= 0 or omitted -> defaults to 50 lines.
                    - A task that already exited stays queryable (its final status + output).

                    #### Security
                    Agent tool (`RiskCategory.AGENT`). Read-only. Scoped to the calling agent - you \
                    can only see your own tasks.
                    """,
            examples = {"{\"taskId\": \"bg-3\"}", "{\"taskId\": \"bg-3\", \"lines\": 20}", "{}"},
            returnExamples = {
                "{\"taskId\": \"bg-3\", \"alive\": true, \"exitCode\": null, \"pid\": 12345, \"uptimeSeconds\": 42,"
                        + " \"command\": \"npm run dev\", \"cwd\": \"/abs/app\", \"recentOutput\": \"VITE ready in 300 ms\"}",
                "{\"count\": 1, \"tasks\": [{\"taskId\": \"bg-3\", \"command\": \"npm run dev\", \"alive\": true,"
                        + " \"exitCode\": null}]}"
            })
    public record Args(
            @Doc("The task id (from run_task). Omit to list every task the calling agent owns.")
                    String taskId,
            @Doc("Max recent output lines to include (default 50).") Integer lines) {}

    @Override
    public @NonNull String getName() {
        return "view_task";
    }

    @Override
    public @NonNull String getDescription() {
        return "Inspect a background task (status + recent output), or list all tasks when taskId"
                + " is omitted.";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(@NonNull Args args) {
        String agentId = currentAgentId();
        int lines = args.lines() != null && args.lines() > 0 ? args.lines() : 50;
        try {
            String taskId = args.taskId();
            if (taskId == null || taskId.isBlank()) {
                List<BackgroundTaskManager.TaskInfo> all = taskManager.list(agentId);
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("count", all.size());
                envelope.put("tasks", all);
                return mapper.writeValueAsString(envelope);
            }
            Optional<BackgroundTaskManager.TaskInfo> info = taskManager.status(agentId, taskId);
            if (info.isEmpty()) {
                return error("task not found: " + taskId);
            }
            Optional<String> out = taskManager.output(agentId, taskId, lines);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("taskId", info.get().taskId());
            envelope.put("alive", info.get().alive());
            Integer exitCode = info.get().exitCode();
            if (exitCode != null) {
                envelope.put("exitCode", exitCode);
            }
            envelope.put("pid", info.get().pid());
            envelope.put("startedAt", info.get().startedAt());
            envelope.put("uptimeSeconds", info.get().uptimeSeconds());
            envelope.put("command", info.get().command());
            envelope.put("cwd", info.get().cwd());
            envelope.put("recentOutput", out.orElse(""));
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            return error("view_task failed: " + e.getMessage());
        }
    }

    private static @NonNull String currentAgentId() {
        var ctx = ToolCallContextHolder.get();
        return ctx != null ? ctx.agentId() : "standalone";
    }

    private static @NonNull String error(@NonNull String message) {
        return "{\"status\":\"error\",\"error\":\"" + jsonEscape(message) + "\"}";
    }

    private static @NonNull String jsonEscape(@NonNull String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
