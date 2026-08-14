package top.focess.veto.group;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import top.focess.veto.model.tier.ModelTier;

/**
 * Per-skillset Mate configuration ({@code veto.group.skillsets.<name>}). Each entry overrides the
 * global Mate defaults ({@code veto.group.mate.tier} / {@code veto.group.mate.system-prompt-base})
 * for Mates spawned under that skillset, so a coding skillset can run on a stronger tier with a
 * coding-focused prompt while a research skillset runs cheaper. This is the seam that makes Mate
 * creation cooperate with the skillset: the group spawner resolves the entry by skillset and passes
 * a {@link MateBinding} to the factory.
 *
 * <pre>
 * veto:
 *   group:
 *     skillsets:
 *       coder:
 *         tier: MID
 *         system-prompt-base: "You are a coding Mate. Implement the assigned task."
 *       researcher:
 *         tier: LOW
 *         system-prompt-base: "You are a research Mate. Gather and summarize."
 * </pre>
 */
@Configuration
@ConfigurationProperties("veto.group")
public class SkillsetProperties {

    private @NonNull Map<String, SkillsetConfig> skillsets = new HashMap<>();

    public @NonNull Map<String, SkillsetConfig> getSkillsets() {
        return skillsets;
    }

    public void setSkillsets(@NonNull Map<String, SkillsetConfig> skillsets) {
        this.skillsets = skillsets;
    }

    /** The config for a skillset, or {@code null} if the skillset has no override. */
    public SkillsetConfig forSkillset(String name) {
        if (name == null) {
            return null;
        }
        return skillsets.get(name);
    }

    /** Per-skillset overrides for a Mate's tier and system-prompt base. */
    public static class SkillsetConfig {

        private ModelTier tier;
        private String systemPromptBase;

        public ModelTier getTier() {
            return tier;
        }

        public void setTier(ModelTier tier) {
            this.tier = tier;
        }

        public String getSystemPromptBase() {
            return systemPromptBase;
        }

        public void setSystemPromptBase(String systemPromptBase) {
            this.systemPromptBase = systemPromptBase;
        }
    }
}
