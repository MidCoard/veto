package top.focess.veto.agent.capability;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.sandbox.BackgroundTaskManager;

@Component
public final class TaskControlCapabilityImpl implements TaskControlCapability {
    private final @NonNull BackgroundTaskManager taskManager;

    public TaskControlCapabilityImpl(@NonNull BackgroundTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public @NonNull List<BackgroundTaskManager.TaskInfo> list() {
        var context = CapabilityAccess.require(ToolCapability.TASK_CONTROL, "view_task");
        return taskManager.list(context.agentId());
    }

    @Override
    public @NonNull Optional<BackgroundTaskManager.TaskInfo> status(@NonNull String taskId) {
        var context = CapabilityAccess.require(ToolCapability.TASK_CONTROL);
        return taskManager.status(context.agentId(), taskId);
    }

    @Override
    public @NonNull Optional<BackgroundTaskManager.TaskInfo> awaitExit(@NonNull String taskId)
            throws InterruptedException {
        var context = CapabilityAccess.require(ToolCapability.TASK_CONTROL, "view_task");
        return taskManager.awaitExit(context.agentId(), taskId);
    }

    @Override
    public @NonNull Optional<String> output(@NonNull String taskId, int lines) {
        var context = CapabilityAccess.require(ToolCapability.TASK_CONTROL, "view_task");
        return taskManager.output(context.agentId(), taskId, Math.min(50, Math.max(0, lines)));
    }

    @Override
    public @NonNull List<@NonNull String> inputFailures(@NonNull String taskId) {
        var context = CapabilityAccess.require(ToolCapability.TASK_CONTROL, "view_task");
        return taskManager.inputFailures(context.agentId(), taskId);
    }

    @Override
    public @NonNull Optional<BackgroundTaskManager.TaskInfo> stop(@NonNull String taskId) {
        var context = CapabilityAccess.require(ToolCapability.TASK_CONTROL, "stop_task");
        return taskManager.stop(
                context.agentId(), taskId, BackgroundTaskManager.ExitCause.AGENT_STOP);
    }

    @Override
    public BackgroundTaskManager.@NonNull InputResult queueInput(
            @NonNull String taskId, byte @NonNull [] bytes, boolean closeStdin) {
        var context = CapabilityAccess.require(ToolCapability.TASK_CONTROL, "input_task");
        UUID sessionId = context.sessionId();
        var binding = context.executionPermit().taskBinding();
        if (binding == null
                || !binding.taskId().equals(taskId)
                || !binding.agentId().equals(context.agentId())
                || !binding.sessionId().equals(sessionId)) {
            throw new SecurityException("Input requires the authorized task instance");
        }
        return taskManager.queueInput(
                context.agentId(),
                binding.sessionId(),
                taskId,
                binding.taskInstanceId(),
                bytes,
                closeStdin);
    }
}
