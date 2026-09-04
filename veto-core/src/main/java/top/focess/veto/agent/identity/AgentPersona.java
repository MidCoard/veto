package top.focess.veto.agent.identity;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.skills.Skill;
import top.focess.veto.agent.tool.ToolDefinition;

/** An agent identity with its role-scoped tool catalog and discoverable skills. */
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

    public @NonNull AgentPersona withWhitelistedTools(@NonNull Set<@NonNull ToolDefinition> tools) {
        return new AgentPersona(id, name, description, tools, registeredSkills, role);
    }

    public @NonNull AgentPersona withRole(@NonNull Role role) {
        return new AgentPersona(id, name, description, whitelistedTools, registeredSkills, role);
    }

    public @NonNull AgentPersona withRoleAndTools(
            @NonNull Role role, @NonNull Set<@NonNull ToolDefinition> tools) {
        return new AgentPersona(id, name, description, tools, registeredSkills, role);
    }
}
