package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.LoopControlCapability;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.ToolCapability;

/** Tool execution through the restricted LoopControl operations. */
public interface LoopControlTool<T> extends AgentTool<T> {
    @NonNull LoopControlCapability loopControlCapability();

    @Override
    default @NonNull ToolCapability getCapability() {
        return ToolCapability.LOOP_CONTROL;
    }

    @NonNull String execute(@NonNull T args, @NonNull LoopControlCapability capability)
            throws Exception;

    @Override
    default @NonNull String execute(@NonNull T args) throws Exception {
        return execute(args, loopControlCapability());
    }
}
