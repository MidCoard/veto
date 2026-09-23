package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.TaskControlCapability;

public interface TaskControlTool<T>
        extends NativeTool<T>, HostCapabilityTool<T, TaskControlCapability> {
    default @NonNull Class<TaskControlCapability> capabilityType() {
        return ToolDocs.nonNullClass(TaskControlCapability.class);
    }

    @NonNull TaskControlCapability taskControlCapability();

    @NonNull String execute(@NonNull T args, @NonNull TaskControlCapability capability);

    @Override
    default @NonNull String execute(@NonNull T args) {
        return execute(args, taskControlCapability());
    }
}
