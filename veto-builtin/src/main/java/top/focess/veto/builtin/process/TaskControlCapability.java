package top.focess.veto.builtin.process;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ToolPreparation;
import top.focess.veto.api.plugin.PluginHost;

public interface TaskControlCapability {
    default @NonNull ToolPreparation prepareInput(
            PluginHost.@NonNull Invocation invocation,
            @NonNull String taskId,
            byte @NonNull [] bytes,
            boolean closeStdin) {
        throw new IllegalStateException("Task preparation unavailable");
    }

    @NonNull List<TaskInfo> list();

    @NonNull Optional<TaskInfo> status(@NonNull String taskId);

    @NonNull Optional<TaskInfo> awaitExit(@NonNull String taskId) throws InterruptedException;

    @NonNull Optional<String> output(@NonNull String taskId, int lines);

    @NonNull List<@NonNull String> inputFailures(@NonNull String taskId);

    @NonNull Optional<TaskInfo> stop(@NonNull String taskId);

    @NonNull InputResult queueInput(
            @NonNull String taskId, byte @NonNull [] bytes, boolean closeStdin);
}
