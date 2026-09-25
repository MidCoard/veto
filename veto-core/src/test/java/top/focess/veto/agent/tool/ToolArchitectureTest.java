package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import top.focess.veto.agent.AgentProfiles;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.contract.AgentConfiguration;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.builtin.group.GroupProfiles;
import top.focess.veto.integration.plugins.PluginManager;

/** Checks that registered tools expose coherent authoring contracts. */
@SpringBootTest
@SuppressWarnings("initialization.field.uninitialized")
class ToolArchitectureTest {
    @Autowired private @NonNull PluginManager plugins;
    @Autowired private @NonNull ApplicationContext context;
    @Autowired private @NonNull ToolEngine engine;

    @Test
    void agentRunnerStaysWithinTheCoordinatorLineBudget() throws Exception {
        var source = Path.of("src/main/java/top/focess/veto/agent/AgentRunner.java");
        long lines;
        try (var content = Files.lines(source)) {
            lines = content.count();
        }
        assertTrue(
                lines <= 1000, "AgentRunner exceeds the 1,000-line architectural limit: " + lines);
    }

    @Test
    void allBuiltinToolsArePluginContributionsWithConfiguredDistributionNames() {
        var entries =
                plugins.catalog().entries(StandardContributionPoints.NATIVE_TOOLS).stream()
                        .filter(entry -> entry.source().namespace().equals("top.focess.builtin"))
                        .toList();
        assertEquals(37, entries.size());
        assertTrue(context.getBeansOfType(NativeTool.class).isEmpty());
        assertTrue(context.getBeansOfType(AgentTool.class).isEmpty());
        for (var entry : entries) {
            String name = plugins.toolName(entry.source().namespace(), entry.id().value());
            assertEquals(entry.id().localId(), name);
            var definition = engine.resolveDefinition(name);
            if (definition == null) throw new AssertionError("Missing built-in: " + name);
            var provenance = definition.provenance();
            if (provenance == null) throw new AssertionError("Not contributed by plugin: " + name);
            assertEquals("top.focess.builtin", provenance.pluginId());
            assertEquals(entry.id().localId(), provenance.localId());
        }
    }

    @Test
    void actualCatalogIdentitySurvivesDefaultNamesAndOperatorAliases() {
        for (String prefix : List.of("plugin_top_focess_builtin__", "operator_")) {
            List<AgentConfiguration.@NonNull Tool> tools = new ArrayList<>();
            for (var entry : plugins.catalog().entries(StandardContributionPoints.NATIVE_TOOLS)) {
                if (!entry.source().namespace().equals("top.focess.builtin")) continue;
                String local = entry.id().localId();
                var registered =
                        ToolRegistration.local(
                                entry.implementation(),
                                prefix + local,
                                plugins.plugin(entry.source().namespace()),
                                local);
                tools.add(AgentProfiles.configurationTool(registered.definition()));
            }
            var base =
                    new AgentProfile(
                            "agent",
                            "agent",
                            "STANDALONE",
                            tools.stream()
                                    .map(AgentConfiguration.Tool::name)
                                    .collect(Collectors.toSet()),
                            null,
                            null,
                            Map.of());
            var session = mock(ToolDocs.nonNullClass(AgentHost.Session.class));
            var configuration =
                    new AgentConfiguration.Context(
                            "owner",
                            new PluginStorage.SessionScope("token", "owner", "session"),
                            session,
                            "agent",
                            base,
                            tools,
                            "");
            var profile = GroupProfiles.standalone(configuration);
            assertTrue(profile.tools().contains(prefix + "create_group"));
            assertTrue(profile.tools().contains(prefix + "view_file"));
            assertFalse(profile.tools().contains(prefix + "create_mate"));
            assertFalse(profile.tools().contains(prefix + "post_message"));
        }
    }

    @Test
    void everyRegisteredToolHasACoherentContract() {
        List<CapabilityTool<?>> tools = new ArrayList<>();
        for (var entry : plugins.catalog().entries(StandardContributionPoints.NATIVE_TOOLS))
            tools.add(entry.implementation());
        assertFalse(tools.isEmpty());
        for (CapabilityTool<?> tool : tools) {
            ToolDefinition definition =
                    ToolRegistration.local(tool, tool.getName(), null).definition();
            assertDoesNotThrow(
                    () -> ToolContractValidator.validateHandler(tool, definition), tool.getName());
            ToolDoc documentation = ToolDocs.toolDocOf(tool.getClass());
            if (documentation == null) {
                throw new AssertionError("Missing ToolDoc on " + tool.getName());
            }
            assertNull(ToolDocs.toolDocOf(tool.getArgsClass()), tool.getName());
            assertEquals(List.of(documentation.examples()), definition.examples(), tool.getName());
            assertEquals(
                    List.of(documentation.returnExamples()),
                    definition.returnExamples(),
                    tool.getName());
            assertEquals(
                    ToolDocs.documentationOf(tool.getClass()),
                    definition.documentation(),
                    tool.getName());
        }
    }
}
