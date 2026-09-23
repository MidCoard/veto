package top.focess.veto.api.agent.capability;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.process.InputResult;
import top.focess.veto.api.process.TaskInfo;

public interface TaskControlCapability extends Capability {
    @NonNull List<TaskInfo> list();

    @NonNull Optional<TaskInfo> status(@NonNull String taskId);

    @NonNull Optional<TaskInfo> awaitExit(@NonNull String taskId) throws InterruptedException;

    @NonNull Optional<String> output(@NonNull String taskId, int lines);

    @NonNull List<@NonNull String> inputFailures(@NonNull String taskId);

    @NonNull Optional<TaskInfo> stop(@NonNull String taskId);

    @NonNull InputResult queueInput(
            @NonNull String taskId, byte @NonNull [] bytes, boolean closeStdin);
}
