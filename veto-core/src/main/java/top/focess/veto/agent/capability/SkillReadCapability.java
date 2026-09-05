package top.focess.veto.agent.capability;

import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.skills.Skill;

public sealed interface SkillReadCapability extends Capability permits SkillReadCapabilityImpl {
    @NonNull Optional<Skill> load(@NonNull String name);
}
