package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.GroupControlCapability;

public interface GroupControlTool<T>
        extends AgentTool<T>, HostCapabilityTool<T, GroupControlCapability> {
    default @NonNull Class<GroupControlCapability> capabilityType() {
        return ToolDocs.nonNullClass(GroupControlCapability.class);
    }

    @NonNull GroupControlCapability groupControlCapability();

    @NonNull String execute(@NonNull T args, @NonNull GroupControlCapability capability);

    @Override
    default @NonNull ToolCapability getCapability() {
        return ToolCapability.GROUP_CONTROL;
    }

    @Override
    default @NonNull String execute(@NonNull T args) {
        return execute(args, groupControlCapability());
    }
}
