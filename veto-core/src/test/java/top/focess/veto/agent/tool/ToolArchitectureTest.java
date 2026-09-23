package top.focess.veto.agent.tool;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.plugin.runtime.PluginManager;

/** Checks that registered tools expose coherent authoring contracts. */
@SpringBootTest
@SuppressWarnings("initialization.field.uninitialized")
class ToolArchitectureTest {
    @Autowired private @NonNull PluginManager plugins;
    @Autowired private @NonNull ApplicationContext context;
    @Autowired private @NonNull ToolEngine engine;

    @Test
    void allBuiltinToolsArePluginContributionsWithTheirOriginalPublicNames() {
        var entries =
                plugins.catalog().entries(StandardContributionPoints.NATIVE_TOOLS).stream()
                        .filter(entry -> entry.source().namespace().equals("top.focess.builtin"))
                        .toList();
        assertEquals(37, entries.size());
        assertTrue(context.getBeansOfType(NativeTool.class).isEmpty());
        assertTrue(context.getBeansOfType(AgentTool.class).isEmpty());
        for (var entry : entries) {
            String name = entry.implementation().getName();
            var definition = engine.resolveDefinition(name);
            if (definition == null) throw new AssertionError("Missing built-in: " + name);
            var provenance = definition.provenance();
            if (provenance == null) throw new AssertionError("Not contributed by plugin: " + name);
            assertEquals("top.focess.builtin", provenance.pluginId());
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
