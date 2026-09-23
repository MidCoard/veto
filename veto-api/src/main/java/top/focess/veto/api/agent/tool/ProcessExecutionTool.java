package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.ProcessExecutionCapability;

public interface ProcessExecutionTool<T>
        extends NativeTool<T>, HostCapabilityTool<T, ProcessExecutionCapability> {
    default @NonNull Class<ProcessExecutionCapability> capabilityType() {
        return ToolDocs.nonNullClass(ProcessExecutionCapability.class);
    }

    @NonNull ProcessExecutionCapability processExecutionCapability();

    @NonNull String execute(@NonNull T args, @NonNull ProcessExecutionCapability capability);

    @Override
    default @NonNull String execute(@NonNull T args) {
        return execute(args, processExecutionCapability());
    }
}
