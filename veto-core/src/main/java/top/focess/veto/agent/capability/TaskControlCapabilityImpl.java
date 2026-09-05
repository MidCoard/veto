package top.focess.veto.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolExecutionException;
import top.focess.veto.agent.tool.ToolJson;
import top.focess.veto.agent.tool.builtin.InputTaskTool;
import top.focess.veto.agent.tool.builtin.StopTaskTool;
import top.focess.veto.agent.tool.builtin.ViewTaskTool;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.sandbox.BackgroundTaskManager;

@Component
public final class TaskControlCapabilityImpl implements TaskControlCapability {
    private static final int RECENT_OUTPUT_LINES = 50;
    private final @NonNull BackgroundTaskManager taskManager;
    private final @NonNull ObjectMapper mapper;

    public TaskControlCapabilityImpl(
            @NonNull BackgroundTaskManager taskManager,
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NonNull ObjectMapper mapper) {
        this.taskManager = taskManager;
        this.mapper = mapper;
    }

    @Override
    public @NonNull String viewTask(ViewTaskTool.@NonNull Args args) {
        var authorized = CapabilityAccess.require(ToolCapability.TASK_CONTROL, "view_task", args);
        String agentId = authorized.agentId();
        try {
            String taskId = args.taskId();
            if (taskId == null || taskId.isBlank()) {
                List<BackgroundTaskManager.TaskInfo> all = taskManager.list(agentId);
                Map<String, Object> envelope = new LinkedHashMap<>();
                envelope.put("count", all.size());
                List<Map<String, Object>> tasks =
                        all.stream()
                                .map(
                                        task -> {
                                            Map<String, Object> item = new LinkedHashMap<>();
                                            item.put("taskId", task.taskId());
                                            item.put("command", task.command());
                                            item.put("alive", task.alive());
                                            Integer exitCode = task.exitCode();
                                            if (exitCode != null) {
                                                item.put("exitCode", exitCode);
                                            }
                                            return item;
                                        })
                                .toList();
                envelope.put("tasks", tasks);
                return mapper.writeValueAsString(envelope);
            }
            Optional<BackgroundTaskManager.TaskInfo> info = taskManager.status(agentId, taskId);
            if (info.isEmpty()) {
                return ToolErrors.failure("task not found: " + taskId);
            }
            Optional<String> out = taskManager.output(agentId, taskId, RECENT_OUTPUT_LINES);
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
            envelope.put("inputFailures", taskManager.inputFailures(agentId, taskId));
            return mapper.writeValueAsString(envelope);
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            return ToolErrors.failure("view_task failed: " + e.getMessage());
        }
    }

    @Override
    public @NonNull String stopTask(StopTaskTool.@NonNull Args args) {
        var authorized = CapabilityAccess.require(ToolCapability.TASK_CONTROL, "stop_task", args);
        String agentId = authorized.agentId();
        Optional<BackgroundTaskManager.TaskInfo> before =
                taskManager.status(agentId, args.taskId());
        if (before.isEmpty()) {
            return ToolErrors.failure("task not found: " + args.taskId());
        }
        boolean wasAlive = before.get().alive();
        Optional<BackgroundTaskManager.TaskInfo> info =
                taskManager.stop(
                        agentId, args.taskId(), BackgroundTaskManager.ExitCause.AGENT_STOP);
        if (info.isEmpty()) {
            return ToolErrors.failure("task not found: " + args.taskId());
        }
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            String status =
                    info.get().alive()
                            ? "stop_requested"
                            : (wasAlive ? "stopped" : "already_exited");
            envelope.put("status", status);
            envelope.put("taskId", info.get().taskId());
            envelope.put("alive", info.get().alive());
            Integer exitCode = info.get().exitCode();
            if (exitCode != null) {
                envelope.put("exitCode", exitCode);
            }
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            return ToolErrors.failure("stop_task failed: " + e.getMessage());
        }
    }

    @Override
    public @NonNull String inputTask(InputTaskTool.@NonNull Args args) {
        var authorized = CapabilityAccess.require(ToolCapability.TASK_CONTROL, "input_task", args);
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
        var context = authorized;
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
