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
    /** Creates a persona with no configuration owner. */
    public AgentPersona(
            @NonNull String id,
            @NonNull String name,
            @NonNull String description,
            @NonNull Set<@NonNull ToolDefinition> tools,
            @NonNull Role role) {
        this(id, name, description, tools, role, null);
    }

    /** Creates a standalone persona with no configuration owner. */
    public AgentPersona(
            @NonNull String id,
            @NonNull String name,
            @NonNull String description,
            @NonNull Set<@NonNull ToolDefinition> tools) {
        this(id, name, description, tools, Role.STANDALONE, null);
    }

    /** A copy of this persona with the given tool whitelist. */
    public @NonNull AgentPersona withWhitelistedTools(@NonNull Set<@NonNull ToolDefinition> tools) {
        return new AgentPersona(id, name, description, tools, role, configurationOwner);
    }

    /** A copy of this persona with the given role. */
    public @NonNull AgentPersona withRole(@NonNull Role role) {
        return new AgentPersona(id, name, description, whitelistedTools, role, configurationOwner);
    }

    /** A copy of this persona with the given role and tool whitelist. */
    public @NonNull AgentPersona withRoleAndTools(
            @NonNull Role role, @NonNull Set<@NonNull ToolDefinition> tools) {
        return new AgentPersona(id, name, description, tools, role, configurationOwner);
    }
}
