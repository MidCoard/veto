package top.focess.veto.api.agent.capability;

import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.skills.Skill;

public interface SkillReadCapability extends Capability {
    @NonNull Optional<Skill> load(@NonNull String name);
}
