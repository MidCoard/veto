package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.LoopControlCapability;

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
