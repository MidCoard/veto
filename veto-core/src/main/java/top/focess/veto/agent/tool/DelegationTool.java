package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.DelegationCapability;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.ToolCapability;

public interface DelegationTool<T> extends AgentTool<T> {
    @NonNull DelegationCapability delegationCapability();

    @NonNull String execute(@NonNull T args, @NonNull DelegationCapability capability);

    @Override
    default @NonNull ToolCapability getCapability() {
        return ToolCapability.DELEGATION;
    }

    @Override
    default @NonNull String execute(@NonNull T args) {
        return execute(args, delegationCapability());
    }
}
