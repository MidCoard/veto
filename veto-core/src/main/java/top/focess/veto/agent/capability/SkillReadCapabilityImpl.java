package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.skills.SkillRegistry;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.builtin.LoadSkillTool;

@Component
public final class SkillReadCapabilityImpl implements SkillReadCapability {
    private final @NonNull SkillRegistry skillRegistry;

    public SkillReadCapabilityImpl(@NonNull SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Override
    public @NonNull String load(LoadSkillTool.@NonNull Args args) throws Exception {
        CapabilityAccess.require(ToolCapability.SKILL_READ, "load_skill", args);

        var skill = skillRegistry.loadVerified(args.skillName());
        if (skill.isEmpty()) {
            return ToolErrors.failure("Skill '" + args.skillName() + "' not found or tampered.");
        }
        String instructions = skill.get().promptInstructions();
        return instructions == null
                ? ToolErrors.failure("Skill body is not loaded.")
                : instructions;
    }
}
