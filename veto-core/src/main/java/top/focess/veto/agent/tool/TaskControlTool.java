package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.TaskControlCapability;

public non-sealed interface TaskControlTool<T> extends NativeTool<T> {
    @NonNull TaskControlCapability taskControlCapability();

    @NonNull String execute(@NonNull T args, @NonNull TaskControlCapability capability);

    @Override
    default @NonNull String execute(@NonNull T args) {
        return execute(args, taskControlCapability());
    }
}
