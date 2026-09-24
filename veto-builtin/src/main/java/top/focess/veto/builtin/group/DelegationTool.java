package top.focess.veto.builtin.group;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.ToolCapability;

/** Plugin-local feature execution; agent host effects are separately authorized. */
public interface DelegationTool<T> extends AgentTool<T> {
    @NonNull DelegationCapability delegationCapability();

    @NonNull String execute(@NonNull T args, @NonNull DelegationCapability operations);

    default @NonNull ToolCapability getCapability() {
        return ToolCapability.PLUGIN_LOCAL;
    }

    default @NonNull String execute(@NonNull T args) {
        return execute(args, delegationCapability());
    }
}
