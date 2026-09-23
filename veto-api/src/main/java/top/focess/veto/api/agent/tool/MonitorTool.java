package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.MonitorCapability;

public interface MonitorTool<T> extends AgentTool<T>, HostCapabilityTool<T, MonitorCapability> {
    default @NonNull Class<MonitorCapability> capabilityType() {
        return ToolDocs.nonNullClass(MonitorCapability.class);
    }

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
