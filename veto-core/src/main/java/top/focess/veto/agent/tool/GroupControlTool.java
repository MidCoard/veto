package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.GroupControlCapability;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.ToolCapability;

public interface GroupControlTool<T> extends AgentTool<T> {
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
