package top.focess.veto.builtin.group;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.ToolCapability;

/** Plugin-local feature execution; agent host effects are separately authorized. */
public interface GroupControlTool<T> extends AgentTool<T> {
    @NonNull GroupControlCapability groupControlCapability();

    @NonNull String execute(@NonNull T args, @NonNull GroupControlCapability operations);

    default @NonNull ToolCapability getCapability() {
        return ToolCapability.PLUGIN_LOCAL;
    }

    default @NonNull String execute(@NonNull T args) {
        return execute(args, groupControlCapability());
    }
}
