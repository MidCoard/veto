package top.focess.veto.api.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.SkillReadCapability;

/** Tool execution through the restricted SkillRead operations. */
public interface SkillReadTool<T> extends AgentTool<T>, HostCapabilityTool<T, SkillReadCapability> {
    default @NonNull Class<SkillReadCapability> capabilityType() {
        return ToolDocs.nonNullClass(SkillReadCapability.class);
    }

    @NonNull SkillReadCapability skillReadCapability();

    @Override
    default @NonNull ToolCapability getCapability() {
        return ToolCapability.SKILL_READ;
    }

    @NonNull String execute(@NonNull T args, @NonNull SkillReadCapability capability)
            throws Exception;

    @Override
    default @NonNull String execute(@NonNull T args) throws Exception {
        return execute(args, skillReadCapability());
    }
}
