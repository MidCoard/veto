package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.ProcessExecutionCapability;

public non-sealed interface ProcessExecutionTool<T> extends NativeTool<T> {
    @NonNull ProcessExecutionCapability processExecutionCapability();

    @NonNull String execute(@NonNull T args, @NonNull ProcessExecutionCapability capability);

    @Override
    default @NonNull String execute(@NonNull T args) {
        return execute(args, processExecutionCapability());
    }
}
