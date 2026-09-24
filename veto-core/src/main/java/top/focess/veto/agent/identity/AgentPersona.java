package top.focess.veto.agent.identity;

import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolDefinition;

/** An agent identity with its host-authorized tool catalogue. */
public record AgentPersona(
        @NonNull String id,
        @NonNull String name,
        @NonNull String description,
        @NonNull Set<@NonNull ToolDefinition> whitelistedTools,
        @NonNull Role role,
        String configurationOwner) {
    public AgentPersona(
            @NonNull String id,
            @NonNull String name,
            @NonNull String description,
            @NonNull Set<@NonNull ToolDefinition> tools,
            @NonNull Role role) {
        this(id, name, description, tools, role, null);
    }

    public AgentPersona(
            @NonNull String id,
            @NonNull String name,
            @NonNull String description,
            @NonNull Set<@NonNull ToolDefinition> tools) {
        this(id, name, description, tools, Role.STANDALONE, null);
    }

    public @NonNull AgentPersona withWhitelistedTools(@NonNull Set<@NonNull ToolDefinition> tools) {
        return new AgentPersona(id, name, description, tools, role, configurationOwner);
    }

    public @NonNull AgentPersona withRole(@NonNull Role role) {
        return new AgentPersona(id, name, description, whitelistedTools, role, configurationOwner);
    }

    public @NonNull AgentPersona withRoleAndTools(
            @NonNull Role role, @NonNull Set<@NonNull ToolDefinition> tools) {
        return new AgentPersona(id, name, description, tools, role, configurationOwner);
    }
}
