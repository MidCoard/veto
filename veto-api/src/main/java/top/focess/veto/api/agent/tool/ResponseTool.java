package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.ResponseCapability;

/** Tool execution through the restricted response-submission operations. */
public interface ResponseTool<T> extends AgentTool<T> {
    @NonNull ResponseCapability responseCapability();

    @Override
    default @NonNull ToolCapability getCapability() {
        return ToolCapability.LOOP_CONTROL;
    }

    @NonNull String execute(@NonNull T args, @NonNull ResponseCapability capability)
            throws Exception;

    @Override
    default @NonNull String execute(@NonNull T args) throws Exception {
        return execute(args, responseCapability());
    }
}
