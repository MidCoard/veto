package top.focess.veto.agent.identity;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.skills.Skill;

/**
 * An agent's cognitive identity + resolved capability manifest.
 *
 * <p>{@code whitelistedTools} holds the resolved {@link ToolDefinition} set (native + external —
 * agent tools are always-on and runtime-excluded from the set). {@code registeredSkills} holds the
 * resolved {@link Skill} list (name+description advertised up-front; full bodies loaded on demand
 * via {@code load_skill}).
 *
 * <p><b>Note:</b> the persona writes the field as {@code Set<Tool>} (a type named {@code Tool}),
 * but no {@code Tool} type is defined anywhere — the manifest type is the sealed {@link
 * ToolDefinition} (the detailed authoritative spec). Per the coordinator's decision, {@code Tool ==
 * ToolDefinition}; the whitelist holds native + remote definitions, with agent tools
 * runtime-excluded (they are always-on, not stored). The system prompt is <b>not</b> held on the
 * persona (it is resolved separately from {@code ~/.veto/}); this record carries only identity +
 * the resolved manifest.
 */
public record AgentPersona(
        String id,
        String name,
        String description,
        Set<ToolDefinition> whitelistedTools,
        List<Skill> registeredSkills,
        String topModel,
        String midModel,
        String lowModel,
        Role role) {

    public AgentPersona(
            String id,
            String name,
            String description,
            Set<ToolDefinition> whitelistedTools,
            List<Skill> registeredSkills) {
        this(
                id,
                name,
                description,
                whitelistedTools,
                registeredSkills,
                "gemini-3.5-flash",
                null,
                null,
                Role.STANDALONE);
    }

    public @NonNull String midModelOrDefault() {
        return midModel != null ? midModel : topModel;
    }

    public @NonNull String lowModelOrDefault() {
        return lowModel != null ? lowModel : topModel;
    }

    /**
     * Returns a copy of this persona with {@code whitelistedTools} replaced by the given set.
     *
     * <p>Used by {@link top.focess.veto.agent.AgentService#createMate} to re-scope a Mate/Leader
     * persona's tools to its {@link Role} (the persona may have been built with the full STANDALONE
     * manifest before its role was known).
     */
    public @NonNull AgentPersona withWhitelistedTools(@NonNull Set<ToolDefinition> tools) {
        return new AgentPersona(
                id, name, description, tools, registeredSkills, topModel, midModel, lowModel, role);
    }
}
