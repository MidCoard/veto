package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolSchemaCompiler;
import top.focess.veto.agent.mcp.tools.GrepSearchTool;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolDefinition;

/**
 * Renders the compiled system prompt for sample personas (STANDALONE/LEADER/MATE across deployer
 * policies) to verify the template + {@link PromptBlocks} linking, and prints them for visual
 * review. This is the "prompt compile / linking" surface: the static template at {@code
 * default-system-prompt.md} with dynamic blocks substituted per role + policy.
 */
class PromptCompileRenderTest {

    private final SystemPromptResolver resolver = new SystemPromptResolver();

    @Test
    void renderStandaloneFullAccess() {
        String prompt = render(Role.STANDALONE, DeployerPolicy.FULL_ACCESS, null, sampleTools());
        System.out.println("===== STANDALONE / FULL_ACCESS =====\n" + prompt);
        assertCompiled(prompt, "You operate directly on the user's workspace", "create_group");
        assertTrue(
                prompt.contains("## Your Tools"),
                "standalone with tools should show the Tools block");
        assertTrue(prompt.contains("FULL_ACCESS deployer policy"));
    }

    @Test
    void renderLeaderProtected() {
        String prompt = render(Role.LEADER, DeployerPolicy.PROTECTED, null, List.of());
        System.out.println("===== LEADER / PROTECTED =====\n" + prompt);
        assertCompiled(
                prompt, "You are the Leader of a delegation group", "do NOT call `create_group`");
        assertFalse(
                prompt.contains("## Your Tools\n"),
                "leader with no tools should drop the Tools block");
        assertTrue(prompt.contains("PROTECTED deployer policy"));
    }

    @Test
    void renderMateSandboxedCustomBase() {
        String prompt =
                render(
                        Role.MATE,
                        DeployerPolicy.SANDBOXED,
                        "You are a Mate agent. Execute the assigned task.",
                        List.of());
        System.out.println("===== MATE / SANDBOXED (custom base) =====\n" + prompt);
        assertCompiled(prompt, "You are a Mate (worker)", "do NOT delegate further");
        assertTrue(prompt.contains("SANDBOXED deployer policy"));
    }

    private String render(
            Role role, DeployerPolicy policy, String base, List<ToolDefinition> tools) {
        Map<String, String> blocks = new LinkedHashMap<>();
        blocks.put("LAW", PromptBlocks.law(""));
        blocks.put(
                "IDENTITY",
                base != null && !base.isBlank()
                        ? base
                        : PromptBlocks.identity(
                                "VetoCoreAgent",
                                "General-purpose engineering assistant for workspace and code"
                                        + " automation."));
        blocks.put("ROLE", PromptBlocks.role(role));
        blocks.put(
                "WORKSPACE",
                PromptBlocks.workspace(
                        Workspace.single(
                                Path.of(System.getProperty("user.dir", ".")), PathMode.REAL)));
        blocks.put("ENVIRONMENT", PromptBlocks.environment());
        blocks.put("TOOLS", PromptBlocks.tools(tools));
        blocks.put("BOUNDARIES", PromptBlocks.boundaries(policy));
        blocks.put("SKILLS", PromptBlocks.skills(List.of()));
        return PromptTemplate.render(resolver.defaultPrompt(), blocks);
    }

    @Test
    void toolsBlockRendersRichCatalog() {
        Map<String, Object> skillArg = new LinkedHashMap<>();
        skillArg.put("type", "string");
        skillArg.put("description", "The name of the skill to load.");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("skillName", skillArg);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("skillName"));
        ToolDefinition tool =
                new ToolDefinition(
                        "load_skill",
                        "Loads a skill.",
                        schema,
                        List.of("{\"skillName\": \"git-rebase\"}", "{\"skillName\": \"deploy\"}"),
                        "#### When to use\nCall this to load a skill.");
        String block = PromptBlocks.tools(List.of(tool));
        assertTrue(block.contains("### `load_skill`"), "tool heading rendered:\n" + block);
        assertTrue(block.contains("**Args:**"), "args label rendered:\n" + block);
        assertTrue(
                block.contains("`skillName` (string, required)"),
                "arg name + type + required rendered:\n" + block);
        assertTrue(
                block.contains("The name of the skill to load."),
                "arg description rendered:\n" + block);
        assertTrue(block.contains("**Examples:**"), "examples label rendered:\n" + block);
        assertTrue(block.contains("git-rebase"), "example content rendered:\n" + block);
        assertTrue(block.contains("#### When to use"), "long description rendered:\n" + block);
    }

    @Test
    void realToolArgsRenderRichCatalog() {
        // Compile the REAL grep_search args record (its @Doc descriptions + @ToolDoc long-form doc
        // + examples) the same way the engine does, then render the catalog block end-to-end.
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> schema =
                mapper.convertValue(
                        ToolSchemaCompiler.compileFromRecord(GrepSearchTool.Args.class), Map.class);
        ToolDefinition tool =
                new ToolDefinition(
                        "grep_search",
                        "Search for exact pattern matches inside files.",
                        schema,
                        ToolDocs.examplesOf(GrepSearchTool.Args.class),
                        ToolDocs.descriptionOf(GrepSearchTool.Args.class));
        String block = PromptBlocks.tools(List.of(tool));
        System.out.println("===== REAL grep_search catalog entry =====\n" + block);
        assertTrue(block.contains("### `grep_search`"), "tool heading rendered:\n" + block);
        assertTrue(
                block.contains("#### When to use"),
                "long-form @ToolDoc description rendered:\n" + block);
        assertTrue(block.contains("#### Security"), "security section rendered:\n" + block);
        assertTrue(
                block.contains("`searchPath` (string, required)"),
                "required string arg typed + flagged:\n" + block);
        assertTrue(
                block.contains("`caseInsensitive` (boolean, optional)"),
                "nullable boolean arg typed + flagged optional:\n" + block);
        assertTrue(
                block.contains("Absolute path to search under."),
                "real @Doc arg description rendered:\n" + block);
        assertFalse(
                ToolDocs.descriptionOf(GrepSearchTool.Args.class).isBlank(),
                "grep_search has a long-form @ToolDoc description");
        assertTrue(
                ToolDocs.examplesOf(GrepSearchTool.Args.class).size() >= 5,
                "grep_search carries many examples");
    }

    private static List<ToolDefinition> sampleTools() {
        return List.of(
                new ToolDefinition(
                        "run_command",
                        "runs discrete commands in the sandbox",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("commands", Map.of(), "cwd", Map.of()))),
                new ToolDefinition(
                        "view_file",
                        "reads lines of a text file",
                        Map.of("type", "object", "properties", Map.of("absolutePath", Map.of()))));
    }

    private static void assertCompiled(String prompt, String... expected) {
        assertFalse(prompt.contains("{{"), "unsubstituted marker remains:\n" + prompt);
        assertFalse(prompt.contains("=== This Turn"), "old Layer-3 header leaked:\n" + prompt);
        for (String e : expected) {
            assertTrue(prompt.contains(e), "missing expected text '" + e + "' in:\n" + prompt);
        }
    }
}
