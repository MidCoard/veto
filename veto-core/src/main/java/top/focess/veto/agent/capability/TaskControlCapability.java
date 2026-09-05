package top.focess.veto.agent.capability;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.veto.sandbox.BackgroundTaskManager;

public sealed interface TaskControlCapability extends Capability permits TaskControlCapabilityImpl {
    @NonNull List<BackgroundTaskManager.TaskInfo> list();

    @NonNull Optional<BackgroundTaskManager.TaskInfo> status(@NonNull String taskId);

    @NonNull Optional<String> output(@NonNull String taskId, int lines);

    @NonNull List<@NonNull String> inputFailures(@NonNull String taskId);

    @NonNull Optional<BackgroundTaskManager.TaskInfo> stop(@NonNull String taskId);

    BackgroundTaskManager.@NonNull InputResult queueInput(
            @NonNull String taskId, byte @NonNull [] bytes, boolean closeStdin);
}
