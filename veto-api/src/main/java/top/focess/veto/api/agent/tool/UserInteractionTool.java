package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.UserInteractionCapability;

/** Tool execution through the restricted UserInteraction operations. */
public interface UserInteractionTool<T>
        extends AgentTool<T>, HostCapabilityTool<T, UserInteractionCapability> {
    default @NonNull Class<UserInteractionCapability> capabilityType() {
        return ToolDocs.nonNullClass(UserInteractionCapability.class);
    }

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
