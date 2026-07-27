package top.focess.veto.agent.mcp.tools;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.AgentTool;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.skills.SkillRegistry;

/**
 * {@code load_skill} — load a skill's full instructions into context as an observation, so the
 * agent can follow its procedure for the current task.
 *
 * <p>Implements {@link AgentTool} with proper Spring constructor injection for {@link
 * SkillRegistry}. The args record {@link LoadSkillArgs} carries the schema source
 * ({@code @ToolDoc}) and the parameter definition; this bean carries the handler logic. Agent tools
 * carry {@link top.focess.veto.agent.mcp.RiskCategory#AGENT}; the Gateway returns {@code
 * NotScreened}.
 */
@Component
public final class LoadSkillTool implements AgentTool<LoadSkillArgs> {

    private final SkillRegistry skillRegistry;

    public LoadSkillTool(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Override
    public @NonNull String getName() {
        return "load_skill";
    }

    @Override
    public @NonNull String getDescription() {
        ToolDoc doc = LoadSkillArgs.class.getAnnotation(ToolDoc.class);
        return (doc != null && !doc.description().isEmpty()) ? doc.description() : "";
    }

    @Override
    public @NonNull Class<LoadSkillArgs> getArgsClass() {
        return LoadSkillArgs.class;
    }

    @Override
    public @NonNull String execute(@NonNull LoadSkillArgs args) throws Exception {
        var skill = skillRegistry.loadVerified(args.skillName());
        if (skill.isEmpty()) {
            return "Skill '" + args.skillName() + "' not found or tampered.";
        }
        return skill.get().promptInstructions();
    }
}
