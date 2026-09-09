package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.MonitorCapability;

public non-sealed interface MonitorTool<T> extends AgentTool<T> {
    @NonNull MonitorCapability monitorCapability();

    @NonNull String execute(@NonNull T args, @NonNull MonitorCapability capability);

    @Override
    default @NonNull ToolCapability getCapability() {
        return ToolCapability.MONITOR_CONTROL;
    }

    @Override
    default @NonNull String execute(@NonNull T args) {
        return execute(args, monitorCapability());
    }
}
