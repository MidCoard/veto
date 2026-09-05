package top.focess.veto.agent.capability;

import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.skills.Skill;
import top.focess.veto.agent.skills.SkillRegistry;
import top.focess.veto.agent.tool.ToolCapability;

@Component
public final class SkillReadCapabilityImpl implements SkillReadCapability {
    private final @NonNull SkillRegistry skillRegistry;

    public SkillReadCapabilityImpl(@NonNull SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Override
    public @NonNull Optional<Skill> load(@NonNull String name) {
        CapabilityAccess.require(ToolCapability.SKILL_READ, "load_skill");
        return skillRegistry.loadVerified(name);
    }
}
