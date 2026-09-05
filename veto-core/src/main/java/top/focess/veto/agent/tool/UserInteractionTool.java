package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.UserInteractionCapability;

/** Tool execution through the restricted UserInteraction operations. */
public non-sealed interface UserInteractionTool<T> extends AgentTool<T> {
    @NonNull UserInteractionCapability userInteractionCapability();

    @Override
    default @NonNull ToolCapability getCapability() {
        return ToolCapability.USER_INTERACTION;
    }

    @NonNull String execute(@NonNull T args, @NonNull UserInteractionCapability capability)
            throws Exception;

    @Override
    default @NonNull String execute(@NonNull T args) throws Exception {
        return execute(args, userInteractionCapability());
    }
}
