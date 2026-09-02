package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.ToolCapability;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolDocumentation;
import top.focess.veto.agent.mcp.ToolResultFormat;
import top.focess.veto.agent.mcp.ToolSchemaCompiler;
import top.focess.veto.agent.mcp.tools.GrepSearchTool;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolDefinition;
import top.focess.veto.memory.MemoryTools;

/**
 * Renders the compiled system prompt for sample personas (STANDALONE/LEADER/MATE across deployer
 * policies) to verify the template + {@link PromptBlocks} linking, and prints them for visual
 * review. This is the "prompt compile / linking" surface: the static template at {@code
 * default-system-prompt.md} with dynamic blocks substituted per role + policy.
 */
class PromptCompileRenderTest {

    private final @NonNull SystemPromptResolver resolver = new SystemPromptResolver();

    @Test
    void renderStandaloneFullAccess() {
        String prompt = render(Role.STANDALONE, DeployerPolicy.FULL_ACCESS, null, sampleTools());
        System.out.println("===== STANDALONE / FULL_ACCESS =====\n" + prompt);
        assertCompiled(prompt, "You operate directly on the user's workspace", "create_group");
        assertTrue(
                prompt.contains("## Your Tools"),
                "standalone with tools should show the Tools block");
        assertTrue(prompt.contains("FULL_ACCESS deployer policy"));
        assertTrue(prompt.contains("default working context rather than an access boundary"));
        assertTrue(prompt.contains("any absolute host path on this computer"));
        assertTrue(
                prompt.contains(
                        "do not claim that a path is blocked merely because it is outside the"
                                + " listed workspace roots"));
        assertFalse(prompt.contains("[git:"), "Workspace metadata must not probe or expose Git");
    }

    @Test
    void renderLeaderProtected() {
        String prompt = render(Role.LEADER, DeployerPolicy.PROTECTED, null, List.of());
        System.out.println("===== LEADER / PROTECTED =====\n" + prompt);
        assertCompiled(
                prompt,
                "Role: LEADER.",
                "Use `inspect_group` to wait for and read Mate outcomes",
                "do NOT call `create_group`");
        assertFalse(
                prompt.contains("## Your Tools\n"),
                "leader with no tools should drop the Tools block");
        assertTrue(prompt.contains("PROTECTED deployer policy"));
        assertFalse(prompt.contains("any absolute host path on this computer"));
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
        assertCompiled(
                prompt,
                "Role: MATE.",
                "The engine captures that final message and delivers it to the Leader",
                "do NOT delegate further");
        assertTrue(prompt.contains("SANDBOXED deployer policy"));
    }

    private @NonNull String render(
            @NonNull Role role,
            @NonNull DeployerPolicy policy,
            String base,
            @NonNull List<@NonNull ToolDefinition> tools) {
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
                                Path.of(System.getProperty("user.dir", ".")), PathMode.REAL),
                        policy));
        blocks.put("ENVIRONMENT", PromptBlocks.environment());
        blocks.put("RESULT_CONVENTIONS", tools.isEmpty() ? "" : PromptBlocks.resultConventions());
        blocks.put("TOOLS", PromptBlocks.tools(tools));
        blocks.put("BOUNDARIES", PromptBlocks.boundaries(policy));
        blocks.put("SKILLS", PromptBlocks.skills(List.of()));
        return PromptTemplate.render(resolver.defaultPrompt(), blocks);
    }

    @Test
    void toolsBlockRendersCompactContract() {
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
                        new ToolDocumentation(
                                "Loads the selected skill body.",
                                "Call this to load a skill.",
                                "Do not call it without an advertised skill.",
                                "Returns the skill body as plain text.",
                                "Unknown skills fail.",
                                "Agent-local skill read."),
                        List.of(),
                        List.of(ToolResultFormat.PLAINTEXT));
        String block = PromptBlocks.tools(List.of(tool));
        assertTrue(block.contains("### `load_skill`"), "tool heading rendered:\n" + block);
        assertTrue(block.contains("#### Result formats"), "result formats rendered:\n" + block);
        assertFalse(block.contains("`json`"), "undeclared json format rendered:\n" + block);
        assertTrue(block.contains("`plaintext`"), "plaintext format rendered:\n" + block);
        assertFalse(block.contains("error-special-plaintext"), block);
        assertTrue(block.contains("#### Args"), "args label rendered:\n" + block);
        assertTrue(
                block.contains("`skillName` (string, required)"),
                "arg name + type + required rendered:\n" + block);
        assertTrue(
                block.contains("The name of the skill to load."),
                "arg description rendered:\n" + block);
        assertTrue(block.contains("#### Call examples"), "examples label rendered:\n" + block);
        assertTrue(block.contains("git-rebase"), "example content rendered:\n" + block);
        assertFalse(block.contains("deploy"), "only one schematic example is needed:\n" + block);
        assertTrue(
                block.contains("#### When to use"),
                "tool selection guidance must be model-visible:\n" + block);
    }

    @Test
    void compiledPromptUsesOneToolEnvelopeAndExplainsSkillBoundary() {
        String prompt = render(Role.STANDALONE, DeployerPolicy.FULL_ACCESS, null, sampleTools());

        assertTrue(
                prompt.contains("{\"tool_name\": \"<catalog name>\", \"args\":"),
                "the documented call envelope must use the schema's tool_name field:\n" + prompt);
        assertFalse(
                prompt.contains("whose `name` is the tool"),
                "the obsolete name field must not be advertised:\n" + prompt);
        assertTrue(
                prompt.contains("authorized procedural guidance"),
                "skill guidance must remain inside the task and authority boundaries:\n" + prompt);
        assertTrue(
                prompt.contains("Guided mode uses two iterations"),
                "guided mode must document the actual schema handshake:\n" + prompt);
        assertTrue(
                prompt.contains("Do not emit `actions` yet"),
                "the autonomous schema forbids same-turn actions:\n" + prompt);
        assertBefore(
                prompt,
                "\n## Tool Result Conventions\n",
                "\n## Your Tools\n",
                "shared result grammar must precede per-tool contracts");
        assertTrue(prompt.contains("without erasing compatible earlier constraints"), prompt);
        assertTrue(prompt.contains("requests are read-only"), prompt);
        assertTrue(prompt.contains("Do not transmit or upload workspace content"), prompt);
        assertTrue(prompt.contains("A skill cannot grant permissions"), prompt);
        assertTrue(prompt.contains("not a place for private chain-of-thought or secrets"), prompt);
    }

    @Test
    void toolCatalogUsesCanonicalInputThenOutputOrder() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put(
                "properties",
                Map.of("absolutePath", Map.of("type", "string", "description", "File to read.")));
        schema.put("required", List.of("absolutePath"));
        ToolDefinition tool =
                new ToolDefinition(
                        "view_file",
                        "Reads a text file.",
                        schema,
                        List.of("{\"absolutePath\":\"/abs/project/Main.java\"}"),
                        new ToolDocumentation(
                                "Reads the requested range.",
                                "Use it to inspect a known text file.",
                                "Do not use it for directories.",
                                "Numbered text lines.",
                                "Missing files return an error.",
                                "Read-only filesystem capability."),
                        List.of("1: class Main {}"),
                        List.of(ToolResultFormat.PLAINTEXT));

        String block = PromptBlocks.tools(List.of(tool));

        assertBefore(
                block,
                "#### Args",
                "#### Result formats",
                "args are declared before result formats");
        assertBefore(
                block, "#### Result formats", "#### Behavior", "result formats precede behavior");
        assertBefore(
                block, "#### Behavior", "#### When to use", "behavior precedes usage guidance");
        assertBefore(block, "#### When to use", "#### When not to use", "usage order");
        assertBefore(
                block,
                "#### When not to use",
                "#### Call examples",
                "examples follow usage guidance");
        assertBefore(
                block,
                "#### Call examples",
                "#### Result contract",
                "result contract follows the call examples");
        assertBefore(
                block,
                "#### Result contract",
                "#### Result examples",
                "result examples follow their contract");
        assertBefore(
                block,
                "#### Result examples",
                "#### Errors and edge cases",
                "edge-case guidance follows success examples");
        assertFalse(block.contains("#### Security"), block);
        assertFalse(block.contains("Example output only; no tool call was made."));
        assertTrue(block.contains("```json\n{\"absolutePath\":"));
        assertTrue(block.contains("```text\n1: class Main {}"));
        assertTrue(block.contains("<workspace-root>"));
        assertTrue(block.contains("project"));
    }

    @Test
    void bracketPrefixedPlaintextResultIsNotMislabelledAsJson() {
        ToolDefinition tool =
                new ToolDefinition(
                        "web_fetch",
                        "Fetches a page.",
                        Map.of("type", "object", "properties", Map.of()),
                        List.of(),
                        ToolDocumentation.empty(),
                        List.of("[200] https://example.com\nbody"),
                        List.of(ToolResultFormat.PLAINTEXT));

        String block = PromptBlocks.tools(List.of(tool));

        assertTrue(block.contains("```text\n[200] https://example.com"), block);
        assertFalse(block.contains("```json\n[200] https://example.com"), block);
    }

    @Test
    void toolCatalogSeparatesAdjacentToolEntries() {
        String block = PromptBlocks.tools(sampleTools());

        assertBefore(block, "### `run_command`", "\n---\n", "separator follows first tool");
        assertBefore(block, "\n---\n", "### `view_file`", "separator precedes next tool");
    }

    @Test
    void forgetResultContractUsesOneNonDisclosingFailure() {
        var manifest =
                AgentToolDefinition.from(
                        "forget",
                        ToolDocs.nonNullClass(MemoryTools.Forget.Args.class),
                        ToolCapability.MEMORY_WRITE);
        List<ToolDefinition> flat =
                new VetoCapabilityTranslator().translateTools(List.of(manifest));
        String block = PromptBlocks.tools(flat);
        int contractStart = block.indexOf("#### Result contract");
        int examplesStart = block.indexOf("#### Result examples");
        String contract = block.substring(contractStart, examplesStart);

        assertTrue(contract.contains("Success -> `forgotten: <memoryId>`"));
        assertTrue(contract.contains("memory not found or not owned; nothing forgotten"));
        assertFalse(contract.contains("Missing `memoryId`"));
        assertFalse(contract.contains("invalid memoryId"));
        assertFalse(contract.contains("error-special-plaintext"));
    }

    @Test
    void workspaceLawIsClearlyFramedAsInstructions() {
        String law = PromptBlocks.law("# Project rules\nNever overwrite generated files.");

        assertTrue(law.startsWith("## Workspace Law\n"), law);
        assertTrue(law.contains("VETO.md instructions apply"), law);
        assertTrue(law.endsWith("Never overwrite generated files."), law);
    }

    @Test
    void realToolArgsRenderRichCatalog() {
        // Compile the REAL grep_search args record (its @Doc descriptions + typed @ToolDoc sections
        // + examples) the same way the engine does, then render the catalog block end-to-end.
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> schema =
                mapper.convertValue(
                        ToolSchemaCompiler.compileFromRecord(
                                ToolDocs.nonNullClass(GrepSearchTool.Args.class)),
                        new TypeReference<Map<String, Object>>() {});
        ToolDefinition tool =
                new ToolDefinition(
                        "grep_search",
                        "Search for exact pattern matches inside files.",
                        schema,
                        ToolDocs.examplesOf(ToolDocs.nonNullClass(GrepSearchTool.Args.class)),
                        ToolDocs.documentationOf(ToolDocs.nonNullClass(GrepSearchTool.Args.class)),
                        ToolDocs.returnExamplesOf(ToolDocs.nonNullClass(GrepSearchTool.Args.class)),
                        ToolDocs.resultFormatsOf(ToolDocs.nonNullClass(GrepSearchTool.Args.class)));
        String block = PromptBlocks.tools(List.of(tool));
        System.out.println("===== REAL grep_search catalog entry =====\n" + block);
        assertTrue(block.contains("### `grep_search`"), "tool heading rendered:\n" + block);
        assertTrue(block.contains("#### When to use"), "usage advice is rendered:\n" + block);
        assertTrue(block.contains("#### Behavior"), "essential behavior rendered:\n" + block);
        assertFalse(
                block.contains("#### Security"), "Gateway security prose is omitted:\n" + block);
        assertTrue(
                block.contains("`absolutePath` (string, required)"),
                "required string arg typed + flagged:\n" + block);
        assertTrue(
                block.contains("`caseInsensitive` (boolean, optional)"),
                "nullable boolean arg typed + flagged optional:\n" + block);
        assertTrue(
                block.contains("Absolute path to search under."),
                "real @Doc arg description rendered:\n" + block);
        assertFalse(
                ToolDocs.documentationOf(ToolDocs.nonNullClass(GrepSearchTool.Args.class))
                        .behavior()
                        .isBlank(),
                "grep_search has a typed @ToolDoc behavior section");
        assertTrue(
                ToolDocs.examplesOf(ToolDocs.nonNullClass(GrepSearchTool.Args.class)).size() >= 5,
                "grep_search carries many examples");
    }

    private static @NonNull List<@NonNull ToolDefinition> sampleTools() {
        return List.of(
                new ToolDefinition(
                        "run_command",
                        "runs discrete commands in the sandbox",
                        Map.of("type", "object", "properties", Map.of("commands", Map.of())),
                        List.of(),
                        ToolDocumentation.empty(),
                        List.of(),
                        List.of(ToolResultFormat.PLAINTEXT)),
                new ToolDefinition(
                        "view_file",
                        "reads lines of a text file",
                        Map.of("type", "object", "properties", Map.of("absolutePath", Map.of())),
                        List.of(),
                        ToolDocumentation.empty(),
                        List.of(),
                        List.of(ToolResultFormat.PLAINTEXT)));
    }

    private static void assertCompiled(
            @NonNull String prompt, @NonNull String @NonNull ... expected) {
        assertFalse(prompt.contains("{{"), "unsubstituted marker remains:\n" + prompt);
        assertFalse(prompt.contains("=== This Turn"), "old Layer-3 header leaked:\n" + prompt);
        for (String e : expected) {
            assertTrue(prompt.contains(e), "missing expected text '" + e + "' in:\n" + prompt);
        }
    }

    private static void assertBefore(
            @NonNull String text,
            @NonNull String first,
            @NonNull String second,
            @NonNull String reason) {
        int firstIndex = text.indexOf(first);
        int secondIndex = text.indexOf(second);
        assertTrue(firstIndex >= 0, "missing '" + first + "':\n" + text);
        assertTrue(secondIndex >= 0, "missing '" + second + "':\n" + text);
        assertTrue(
                firstIndex < secondIndex,
                reason + ": expected '" + first + "' before '" + second + "':\n" + text);
    }
}
