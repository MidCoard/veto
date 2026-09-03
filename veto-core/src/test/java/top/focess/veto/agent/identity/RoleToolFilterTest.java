package top.focess.veto.agent.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.ToolCapability;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.mcp.ToolEngine;
import top.focess.veto.agent.mcp.ToolResult;
import top.focess.veto.llm.core.ToolCall;

class RoleToolFilterTest {

    private static final @NonNull List<ToolDefinition> TOOLS =
            List.of(
                    tool("read", ToolCapability.WORKSPACE_READ),
                    tool("think", ToolCapability.LOOP_CONTROL),
                    tool("create_group", ToolCapability.DELEGATION),
                    tool("post_message", ToolCapability.GROUP_CONTROL),
                    tool("unclassified", ToolCapability.AGENT_CONTROL));

    @Test
    void roleSelectionUsesCapabilitiesAndUnclassifiedToolsFailClosed() {
        RoleToolFilter filter = new RoleToolFilter(engine());

        assertEquals(
                Set.of("read", "think", "create_group"),
                names(filter.resolve(Role.STANDALONE)));
        assertEquals(Set.of("read", "think"), names(filter.resolve(Role.MATE)));
        assertEquals(Set.of("read", "think", "post_message"), names(filter.resolve(Role.LEADER)));
        for (Role role : Role.values()) {
            assertFalse(names(filter.resolve(role)).contains("unclassified"));
        }
    }

    @Test
    void narrowerSelectionCannotEscapeRoleCeiling() {
        RoleToolFilter filter = new RoleToolFilter(engine());

        Set<@NonNull ToolDefinition> selected =
                filter.resolve(
                        Role.MATE,
                        Set.of(ToolCapability.WORKSPACE_READ, ToolCapability.GROUP_CONTROL));

        assertEquals(Set.of("read"), names(selected));
        assertTrue(
                selected.stream()
                        .allMatch(
                                tool ->
                                        tool.capability()
                                                == ToolCapability.WORKSPACE_READ));
    }

    private static @NonNull AgentToolDefinition tool(
            @NonNull String name, @NonNull ToolCapability capability) {
        return new AgentToolDefinition(name, name, capability, Object.class, Map.of());
    }

    private static @NonNull ToolEngine engine() {
        return new ToolEngine() {
            @Override
            public @NonNull List<ToolDefinition> getActiveTools(Set<String> whitelist) {
                return TOOLS;
            }

            @Override
            public ToolDefinition resolveDefinition(@NonNull String toolName) {
                return TOOLS.stream()
                        .filter(tool -> tool.name().equals(toolName))
                        .findFirst()
                        .orElse(null);
            }

            @Override
            public @NonNull ToolResult execute(
                    @NonNull ToolCall call, @NonNull ToolDefinition definition) {
                return new ToolResult(call.toolName(), call.callId(), true, "");
            }
        };
    }

    private static @NonNull Set<@NonNull String> names(
            @NonNull Set<@NonNull ToolDefinition> tools) {
        return tools.stream().map(ToolDefinition::name).collect(Collectors.toUnmodifiableSet());
    }
}
