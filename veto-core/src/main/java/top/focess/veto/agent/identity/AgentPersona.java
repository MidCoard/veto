package top.focess.veto.agent.identity;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.skills.Skill;

/**
 * An agent's cognitive identity + resolved capability manifest.
 *
 * <p>{@code whitelistedTools} holds the resolved, role-scoped {@link ToolDefinition} set (native,
 * agent, and remote definitions after {@link RoleToolFilter} filtering). {@code registeredSkills}
 * holds the resolved {@link Skill} list (name+description advertised up-front; full bodies loaded
 * on demand via {@code load_skill}).
 *
 * <p><b>Note:</b> the persona writes the field as {@code Set<Tool>} (a type named {@code Tool}),
 * but no {@code Tool} type is defined anywhere - the manifest type is the sealed {@link
 * ToolDefinition} (the detailed authoritative spec). The whitelist therefore holds the current
 * role's complete resolved manifest. The system prompt is <b>not</b> held on the persona (the
 * current resolver loads the bundled prompt resource); this record carries only identity + the
 * resolved manifest.
 *
 * <p>The model tier is <b>not</b> held on the persona either. The persona names no concrete model
 * and no tier - the tier is owned by the {@link top.focess.veto.model.AgentEntity} (for
 * STANDALONE/LEADER, sourced from the {@link top.focess.veto.model.AgentPatternEntity}) or carried
 * by the {@link top.focess.veto.group.MateBinding} (for Mates), and resolved to a concrete provider
 * + model + credential by the {@link top.focess.veto.model.tier.ModelTierRegistry}. Keeping it off
 * the persona means a tier-profile switch re-points every agent's model without touching identity.
 */
public record AgentPersona(
        @NonNull String id,
        @NonNull String name,
        @NonNull String description,
        @NonNull Set<@NonNull ToolDefinition> whitelistedTools,
        @NonNull List<@NonNull Skill> registeredSkills,
        @NonNull Role role) {

    public AgentPersona(
            @NonNull String id,
            @NonNull String name,
            @NonNull String description,
            @NonNull Set<@NonNull ToolDefinition> whitelistedTools,
            @NonNull List<@NonNull Skill> registeredSkills) {
        this(id, name, description, whitelistedTools, registeredSkills, Role.STANDALONE);
    }

    /**
     * Returns a copy of this persona with {@code whitelistedTools} replaced by the given set.
     *
     * <p>Used by {@link top.focess.veto.agent.AgentService#createMate} to re-scope a Mate/Leader
     * persona's tools to its {@link Role} (the persona may have been built with the full STANDALONE
     * manifest before its role was known).
     */
    public @NonNull AgentPersona withWhitelistedTools(@NonNull Set<@NonNull ToolDefinition> tools) {
        return new AgentPersona(id, name, description, tools, registeredSkills, role);
    }

    /**
     * Returns a copy of this persona with the role replaced (identity, tools, and skills
     * preserved). Used by the delegation transform: the same agent row adopts the Leader (or back
     * to STANDALONE) operational role without becoming a different agent - state, not type.
     */
    public @NonNull AgentPersona withRole(@NonNull Role role) {
        return new AgentPersona(id, name, description, whitelistedTools, registeredSkills, role);
    }

    /**
     * Returns a copy of this persona with both the role and the tool set replaced. The transform
     * re-scopes the standalone manifest to the Leader's allow-list in the same step it flips the
     * role, so the next compile emits the Leader system message + Leader tool catalog atomically.
     */
    public @NonNull AgentPersona withRoleAndTools(
            @NonNull Role role, @NonNull Set<@NonNull ToolDefinition> tools) {
        return new AgentPersona(id, name, description, tools, registeredSkills, role);
    }
}
