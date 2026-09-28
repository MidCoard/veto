package top.focess.veto.builtin.group;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.ToolCapability;

/** Plugin-local feature execution; agent host effects are separately authorized. */
public abstract class GroupControlTool<T> extends AgentTool<T> {
    public abstract @NonNull GroupControlCapability groupControlCapability();

    public abstract @NonNull String execute(
            @NonNull T args, @NonNull GroupControlCapability operations);

    public @NonNull ToolCapability getCapability() {
        return ToolCapability.PLUGIN_LOCAL;
    }

    public @NonNull String execute(@NonNull T args) {
        return execute(args, groupControlCapability());
    }
}
