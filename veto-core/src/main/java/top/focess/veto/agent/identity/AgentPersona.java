package top.focess.veto.agent.identity;

import java.util.List;
import java.util.Set;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.skills.Skill;

/**
 * An agent's cognitive identity + resolved capability manifest. {@code
 * plans/mvp-core/part5_agent/agent_identity_persona.md}.
 *
 * <p>{@code whitelistedTools} holds the resolved {@link ToolDefinition} set (native + external —
 * agent tools are always-on and runtime-excluded from the set). {@code registeredSkills} holds the
 * resolved {@link Skill} list (name+description advertised up-front; full bodies loaded on demand
 * via {@code load_skill}).
 *
 * <p><b>Phase-0 contract note:</b> the persona writes the field as {@code Set<Tool>} (a type named
 * {@code Tool}), but no {@code Tool} type is defined anywhere — the manifest type is the sealed
 * {@link ToolDefinition} (the detailed authoritative spec). Per the coordinator's decision, {@code
 * Tool == ToolDefinition}; the whitelist holds native + remote definitions, with agent tools
 * runtime-excluded (they are always-on, not stored). The system prompt is <b>not</b> held on the
 * persona (persona — it is resolved separately from {@code ~/.veto/}); this record carries only
 * identity + the resolved manifest.
 */
public record AgentPersona(
        String id,
        String name,
        String description,
        Set<ToolDefinition> whitelistedTools,
        List<Skill> registeredSkills) {}
