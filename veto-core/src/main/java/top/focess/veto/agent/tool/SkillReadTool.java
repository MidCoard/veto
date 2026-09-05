package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.SkillReadCapability;

/** Tool execution through the restricted SkillRead operations. */
public non-sealed interface SkillReadTool<T> extends AgentTool<T> {
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
