package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.builtin.LoadSkillTool;

/** Operations restricted to the current authorized call and caller. */
public sealed interface SkillReadCapability extends Capability permits SkillReadCapabilityImpl {
    @NonNull String load(LoadSkillTool.@NonNull Args args) throws Exception;
}
