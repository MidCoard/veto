package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.tool.AgentToolDefinition;
import top.focess.veto.agent.tool.ToolPresentations;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolPresentation;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.resources.CatalogueTree;
import top.focess.veto.builtin.tools.LoadSkillTool;

class ToolAvailabilityTest {
    @Test
    void availableToolOwnsItsMdcCatalogueUnderRenamedIdentity(@TempDir @NonNull Path root) {
        var definition =
                new AgentToolDefinition(
                        "operator_skill",
                        "Skills",
                        ToolCapability.PLUGIN_LOCAL,
                        Danger.SAFE,
                        ToolDocs.nonNullClass(LoadSkillTool.class),
                        ToolDocs.nonNullClass(LoadSkillTool.Args.class),
                        Map.of(),
                        null,
                        tree ->
                                new ToolPresentation.State(
                                        true,
                                        Map.of(
                                                "skills",
                                                List.of(
                                                        Map.of(
                                                                "name",
                                                                "review",
                                                                "description",
                                                                "Review safely")))));
        var compiler =
                new PromptCompiler(
                        new VetoCapabilityTranslator(),
                        new SystemPromptResolver(),
                        new ObjectMapper(),
                        "FULL_ACCESS");
        var rendered =
                compiler.linkSystemSource(
                        new AgentPersona("id", "name", "", Set.of(definition)),
                        Workspace.single(root, PathMode.REAL),
                        null,
                        ToolResultPresentationMode.BASIC);
        assertTrue(rendered.text().contains("## Available skills"));
        assertTrue(rendered.text().contains("review: Review safely"));
        assertTrue(
                rendered.sources().stream()
                        .anyMatch(span -> span.source().equals("builtin-skills.mdc")));
    }

    @Test
    void availabilityIsGenericAndRetainedPresentationGrantExpires(@TempDir @NonNull Path root) {
        var captured = new AtomicReference<CatalogueTree>();
        var hidden =
                new AgentToolDefinition(
                        "renamed_tool",
                        "Test",
                        ToolCapability.PLUGIN_LOCAL,
                        Danger.SAFE,
                        ToolDocs.nonNullClass(ToolAvailabilityTest.class),
                        ToolDocs.nonNullClass(ToolAvailabilityTest.class),
                        Map.of(),
                        null,
                        tree -> {
                            captured.set(tree);
                            return new ToolPresentation.State(false, Map.of());
                        });
        var ordinary =
                new AgentToolDefinition(
                        "ordinary",
                        "Test",
                        ToolCapability.PLUGIN_LOCAL,
                        Danger.SAFE,
                        ToolDocs.nonNullClass(ToolAvailabilityTest.class),
                        ToolDocs.nonNullClass(ToolAvailabilityTest.class),
                        Map.of());
        assertEquals(
                List.of(ordinary),
                PromptCompiler.availableTools(List.of(hidden, ordinary), List.of(root)));
        assertThrows(
                SecurityException.class,
                () -> ToolPresentations.requireAvailable(hidden, List.of(root)));
        var retained = captured.get();
        assertNotNull(retained);
        assertThrows(SecurityException.class, () -> retained.files("", "SKILL.md"));
    }
}
