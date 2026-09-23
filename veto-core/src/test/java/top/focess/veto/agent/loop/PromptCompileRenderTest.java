package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.tool.AgentToolDefinition;
import top.focess.veto.agent.tool.ToolSchemaCompiler;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolDocumentation;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.llm.ToolDefinition;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.builtin.memory.MemoryTools;
import top.focess.veto.builtin.tools.RunCommandTool;
import top.focess.veto.builtin.workspace.GrepSearchTool;

/**
 * Renders the compiled system prompt for sample personas (STANDALONE/LEADER/MATE across deployer
 * policies) to verify the template + {@link PromptBlocks} linking, and prints them for visual
 * review. This is the "prompt compile / linking" surface: the static template at {@code
 * default-system-prompt.md} with dynamic blocks substituted per role + policy.
 */
class PromptCompileRenderTest {
    @Test
    void catalogueRendersArgumentsFromTheNativeSchema() {
        var manifest =
                AgentToolDefinition.from(
                        "run_command",
                        ToolDocs.nonNullClass(RunCommandTool.class),
                        ToolDocs.nonNullClass(RunCommandTool.Args.class),
                        ToolCapability.PROCESS_EXECUTION);
        var tool = new VetoCapabilityTranslator().translateTools(List.of(manifest)).getFirst();
        var schema = new ObjectMapper().valueToTree(tool.inputSchema());
        assertEquals("array", schema.at("/properties/commands/type").asText());
        assertEquals("object", schema.at("/properties/commands/items/type").asText());
        assertEquals(
                "array", schema.at("/properties/commands/items/properties/args/type").asText());
        assertEquals(
                "string",
                schema.at("/properties/commands/items/properties/args/items/type").asText());
        assertTrue(schema.path("required").toString().contains("commands"));
        String rendered = PromptBlocks.tools(List.of(tool));
        assertTrue(
                rendered.contains(
                        "The arguments of each tool are listed from its native schema, the same"
                                + " definition sent to the provider with every request."));
        assertTrue(rendered.contains("#### Arguments"));
        assertTrue(rendered.contains("(array<object>, required)"));
    }

    private final @NonNull SystemPromptResolver resolver = new SystemPromptResolver();

    @Test
    void toolResultConventionsDescribeTheSelectedSessionRepresentation() {
        String basic = PromptBlocks.resultConventions(ToolResultPresentationMode.BASIC);
        String detailed = PromptBlocks.resultConventions(ToolResultPresentationMode.DETAILED);

        assertTrue(basic.contains("no common object surrounds it"));
        assertFalse(basic.contains("exactly four fields"));
        assertTrue(detailed.contains("exactly four fields"));
        assertTrue(
                detailed.contains(
                        "`success`, `failure`, `refused`, `cancelled`, or `interrupted`"));
        assertTrue(detailed.contains("`json`, `plaintext`, or `unknown`"));
        assertTrue(detailed.contains("`content` is always a string"));
        assertTrue(
                detailed.contains(
                        "`errorCode` is either a stable machine-readable string or `null`"));
    }

    @Test
    void renderStandaloneFullAccess() {
        String prompt = render(Role.STANDALONE, DeployerPolicy.FULL_ACCESS, null, sampleTools());
        System.out.println("===== STANDALONE / FULL_ACCESS =====\n" + prompt);
        assertCompiled(
                prompt, "Complete the user's request using the conversation", "### `run_command`");
        assertFalse(
                prompt.contains("create_group"), "unavailable delegation must not be advertised");
        assertFalse(prompt.contains("## How to delegate"));
        assertTrue(
                prompt.contains("## Your tools"),
                "standalone with tools should show the Tools block");
        assertFalse(prompt.contains("FULL_ACCESS"));
        assertFalse(prompt.contains("Path mode:"));
        assertTrue(prompt.contains("Use an absolute path for every file-tool path argument"));
        assertTrue(
                prompt.contains("Filesystem access is not limited to the listed workspace roots"));
        assertTrue(prompt.contains("default working context, not an access boundary"));
        assertTrue(prompt.contains("any absolute host path"));
        assertTrue(
                prompt.contains(
                        "do not claim that an outside path is blocked merely because it is outside"
                                + " these roots"));
        assertFalse(prompt.contains("[git:"), "Workspace metadata must not probe or expose Git");
    }

    @Test
    void renderLeaderProtected() {
        String prompt = render(Role.LEADER, DeployerPolicy.PROTECTED, null, List.of());
        System.out.println("===== LEADER / PROTECTED =====\n" + prompt);
        assertCompiled(
                prompt,
                "Coordinate the group, review its results, and answer the user.",
                "Results arrive automatically as Monitor observations.",
                "Use read-only investigation to understand the task and verify returned evidence.",
                "Do not carry out delegated changes yourself or call `create_group`.");
        assertFalse(
                prompt.contains("## Your tools\n"),
                "leader with no tools should drop the Tools block");
        assertFalse(prompt.contains("PROTECTED"));
        assertTrue(prompt.contains("Protected targets cannot be accessed or approved"));
        assertTrue(prompt.contains("outside workspace roots remain addressable"));
        assertFalse(prompt.contains("another user's unshared workspace"));
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
                "Complete the work assigned to you within the delegation group.",
                "The runtime records your final message and delivers it to the Leader",
                "Do not delegate further",
                "You may recall existing session memories and insights from other sessions",
                "cannot create, promote, delete, or otherwise change those memories or insights");
        assertTrue(prompt.contains("## Additional role guidance"));
        assertTrue(prompt.contains("You are a Mate agent. Execute the assigned task."));
        assertFalse(prompt.contains("SANDBOXED"));
        assertTrue(prompt.contains("session workspace roots are hard path boundaries"));
        assertFalse(prompt.contains("owner has shared it"));
    }

    @Test
    void everyDeployerPolicyHasADistinctBoundaryContract() {
        String full = render(Role.STANDALONE, DeployerPolicy.FULL_ACCESS, null, List.of());
        String protectedPrompt = render(Role.STANDALONE, DeployerPolicy.PROTECTED, null, List.of());
        String sandboxed = render(Role.STANDALONE, DeployerPolicy.SANDBOXED, null, List.of());
        String tenant = render(Role.STANDALONE, DeployerPolicy.TENANT, null, List.of());

        assertTrue(full.contains("Filesystem access is not limited"));
        assertTrue(protectedPrompt.contains("Protected targets cannot be accessed or approved"));
        assertTrue(sandboxed.contains("session workspace roots are hard path boundaries"));
        assertTrue(tenant.contains("listed workspace roots"));
        assertFalse(tenant.contains("sharing"));
        assertFalse(full.equals(protectedPrompt));
        assertFalse(protectedPrompt.equals(sandboxed));
        assertFalse(sandboxed.equals(tenant));
    }

    @Test
    void everyRoleCompilesUnderEveryDeployerPolicy() {
        for (Role role : List.of(Role.STANDALONE, Role.LEADER, Role.MATE)) {
            for (DeployerPolicy policy :
                    List.of(
                            DeployerPolicy.FULL_ACCESS,
                            DeployerPolicy.PROTECTED,
                            DeployerPolicy.SANDBOXED,
                            DeployerPolicy.TENANT)) {
                String prompt = render(role, policy, null, List.of());
                assertCompiled(prompt, "## Your role", "## Boundaries");
                assertFalse(prompt.contains("Role: " + role + "."), prompt);
                assertFalse(prompt.contains(policy.name()), prompt);
                for (String internalName :
                        List.of(
                                "FULL_ACCESS",
                                "PROTECTED",
                                "SANDBOXED",
                                "TENANT",
                                "Path mode:",
                                "Gateway",
                                "screening",
                                "jailbreak",
                                "auditing")) {
                    assertFalse(prompt.contains(internalName), prompt);
                }
                assertFalse(prompt.contains("For a Mate"), prompt);
                assertFalse(prompt.contains("veto_pulse"), prompt);
            }
        }
    }

    @Test
    void virtualPathModeDoesNotClaimUnrestrictedHostReachability() {
        String workspace =
                PromptBlocks.workspace(
                        Workspace.single(
                                Path.of(System.getProperty("user.dir", ".")), PathMode.VIRTUAL));
        String boundaries = PromptBlocks.boundaries(DeployerPolicy.FULL_ACCESS, PathMode.VIRTUAL);

        assertTrue(workspace.contains("mounted workspace roots"));
        assertTrue(boundaries.contains("only the mounted workspace roots"));
        assertFalse(workspace.contains("Path mode:"));
        assertFalse(workspace.contains("VIRTUAL"));
        assertFalse(boundaries.contains("FULL_ACCESS"));
        assertFalse(boundaries.contains("any absolute host path"));
    }

    @Test
    void environmentContainsHostFactsButNotToolInstructionsOrConcreteExamples() {
        String environment = PromptBlocks.environment();

        assertTrue(environment.contains("## Environment"));
        assertFalse(environment.contains("run_command"));
        assertFalse(environment.contains("gradlew"));
        assertFalse(environment.contains("mvnw"));
        assertFalse(environment.contains("npm.cmd"));
        assertFalse(environment.contains("Main.java"));
        assertTrue(
                ToolDocs.documentationOf(ToolDocs.nonNullClass(RunCommandTool.class))
                        .behavior()
                        .contains("there is no shell"));
    }

    private @NonNull String render(
            @NonNull Role role,
            @NonNull DeployerPolicy policy,
            String base,
            @NonNull List<@NonNull ToolDefinition> tools) {
        var persona =
                new AgentPersona(
                        "test",
                        "VetoCoreAgent",
                        SystemPromptResolver.DESCRIPTION,
                        Set.of(),
                        List.of(),
                        role);
        return PromptLibrary.text(
                "default-system-prompt",
                PromptInputs.standard(
                        persona,
                        Workspace.single(
                                Path.of(System.getProperty("user.dir", ".")), PathMode.REAL),
                        base,
                        tools,
                        policy,
                        ToolResultPresentationMode.BASIC));
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
                        List.of(
                                "{\"skillName\": \"git-rebase\"}",
                                "{\"skillName\": \"deploy\"}",
                                "{\"skillName\": \"rollback\"}",
                                "{\"skillName\": \"release\"}",
                                "{\"skillName\": \"hotfix\"}",
                                "{\"skillName\": \"archive\"}"),
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
        assertTrue(block.contains("#### Arguments"), "argument list rendered:\n" + block);
        assertTrue(block.contains("`skillName` (string, required)"), block);
        assertTrue(block.contains("The name of the skill to load."), block);
        var nativeSchema = new ObjectMapper().valueToTree(tool.inputSchema());
        assertEquals("string", nativeSchema.at("/properties/skillName/type").asText());
        assertTrue(nativeSchema.path("required").toString().contains("skillName"));
        assertTrue(block.contains("#### Argument examples"), "example label rendered:\n" + block);
        assertTrue(block.contains("git-rebase"), "first declared example rendered:\n" + block);
        assertTrue(block.contains("hotfix"), "up to five declared examples render:\n" + block);
        assertFalse(
                block.contains("archive"),
                "examples beyond the five-example cap are dropped:\n" + block);
        assertTrue(
                block.contains("#### When to use"),
                "tool selection guidance must be model-visible:\n" + block);
    }

    @Test
    void compiledPromptExplainsNativeToolsTextRepliesAndSkillBoundary() {
        String prompt = render(Role.STANDALONE, DeployerPolicy.FULL_ACCESS, null, sampleTools());

        assertFalse(prompt.contains("\"calls\""), "JSON calls must not be advertised");
        assertFalse(
                prompt.contains("whose `name` is the tool"),
                "the obsolete name field must not be advertised:\n" + prompt);
        assertTrue(
                prompt.contains("procedural guidance from Veto's configured skill registry"),
                "skill guidance must remain inside the task and authority boundaries:\n" + prompt);
        assertFalse(prompt.contains("Guided mode uses two iterations"));
        assertFalse(
                prompt.contains("conditional_goto"),
                "disabled prompt must not advertise guided programs");
        assertBefore(
                prompt,
                "\n## Tool result conventions\n",
                "\n## Your tools\n",
                "shared result grammar must precede per-tool contracts");
        assertTrue(prompt.contains("while keeping earlier requirements that still apply"), prompt);
        assertTrue(prompt.contains("as read-only unless the user also requests a change"), prompt);
        assertTrue(
                prompt.contains(
                        "Send workspace content, source code, personal data, or secrets to an external destination only when"),
                prompt);
        assertTrue(prompt.contains("A skill cannot grant permission"), prompt);
        assertTrue(
                prompt.contains("Keep secrets out of URLs, query strings, command arguments"),
                prompt);
        assertFalse(
                prompt.contains("For a Mate"),
                "shared response rules must not leak Mate-only context into other roles");
        assertFalse(prompt.contains("matching the response schema"), prompt);
        assertFalse(prompt.contains("When native tool execution is enabled"), prompt);
        assertTrue(prompt.contains("For conversation answers, use the requested format"), prompt);
        assertTrue(prompt.contains("No response envelope is required."), prompt);
        assertFalse(prompt.contains("two invocation formats for the same tools"), prompt);
        assertFalse(prompt.contains("Call them by populating the `calls` array"), prompt);
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

        assertTrue(block.contains("#### Arguments"));
        assertTrue(block.contains("`absolutePath` (string, required): File to read."));
        assertBefore(
                block, "#### Arguments", "#### Result formats", "arguments precede result formats");
        assertBefore(
                block, "#### Result formats", "#### Behavior", "result formats precede behavior");
        assertBefore(
                block, "#### Behavior", "#### When to use", "behavior precedes usage guidance");
        assertBefore(block, "#### When to use", "#### When not to use", "usage order");
        assertBefore(
                block,
                "#### When not to use",
                "#### Argument examples",
                "examples follow usage guidance");
        assertBefore(
                block,
                "#### Argument examples",
                "#### Result contract",
                "result contract follows the call example");
        assertBefore(
                block,
                "#### Result contract",
                "#### Result examples",
                "result example follows its contract");
        assertBefore(
                block,
                "#### Result examples",
                "#### Errors and edge cases",
                "edge-case guidance follows the success example");
        assertBefore(
                block,
                "#### Errors and edge cases",
                "#### Security",
                "security guidance closes the entry");
        assertTrue(block.contains("Read-only filesystem capability."), block);
        assertFalse(block.contains("Example output only; no tool call was made."));
        assertTrue(block.contains("```json\n{\"absolutePath\":"));
        assertTrue(block.contains("```text\n1: class Main {}"));
        assertTrue(
                block.contains("/abs/project/Main.java"),
                "External examples remain literal data; owned examples are authored in MDC.");
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
    void toolCatalogListsToolsByNameWithoutInternalCategories() {
        String block = PromptBlocks.tools(sampleTools());
        assertBefore(block, "### `run_command`", "### `view_file`", "tools are ordered by name");
        assertFalse(block.contains("Tool capability"), block);
        assertFalse(block.contains("Workspace Read"), block);
        assertFalse(block.contains("Process Execution"), block);
    }

    @Test
    void forgetResultContractUsesOneNonDisclosingFailure() {
        // Java class literals are non-null; Checker treats this nested literal as nullable.
        @SuppressWarnings("nullness:assignment")
        @NonNull Class<?> toolClass = MemoryTools.ForgetMemory.class;
        var manifest =
                AgentToolDefinition.from(
                        "forget_memory",
                        toolClass,
                        ToolDocs.nonNullClass(MemoryTools.ForgetMemory.Args.class),
                        ToolCapability.MEMORY_WRITE);
        List<ToolDefinition> flat =
                new VetoCapabilityTranslator().translateTools(List.of(manifest));
        String block = PromptBlocks.tools(flat);
        int contractStart = block.indexOf("#### Result contract");
        int examplesStart = block.indexOf("#### Result examples");
        String contract = block.substring(contractStart, examplesStart);

        assertTrue(contract.contains("Success -> `forgotten: <memoryId>`"));
        assertTrue(
                contract.contains(
                        "Memory not found: the memory does not exist or is not owned; nothing forgotten."));
        assertFalse(contract.contains("Missing `memoryId`"));
        assertFalse(contract.contains("invalid memoryId"));
        assertFalse(contract.contains("error-special-plaintext"));
    }

    @Test
    void workspaceLawIsClearlyFramedAsInstructions() {
        String law = PromptBlocks.law("# Project rules\nNever overwrite generated files.");

        assertTrue(law.startsWith("## Workspace law\n"), law);
        assertTrue(law.contains("VETO.md instructions apply"), law);
        assertTrue(law.endsWith("Never overwrite generated files."), law);
    }

    @Test
    void realToolKeepsNativeArgumentsAndBehaviorCatalog() {
        // Compile the real grep_search parameter schema and tool-class documentation the same way
        // the engine does, then render the catalog block end-to-end.
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
                        ToolDocs.examplesOf(ToolDocs.nonNullClass(GrepSearchTool.class)),
                        ToolDocs.documentationOf(ToolDocs.nonNullClass(GrepSearchTool.class)),
                        ToolDocs.returnExamplesOf(ToolDocs.nonNullClass(GrepSearchTool.class)),
                        ToolDocs.resultFormatsOf(ToolDocs.nonNullClass(GrepSearchTool.class)));
        String block = PromptBlocks.tools(List.of(tool));
        System.out.println("===== REAL grep_search catalog entry =====\n" + block);
        assertTrue(block.contains("### `grep_search`"), "tool heading rendered:\n" + block);
        assertTrue(block.contains("#### When to use"), "usage advice is rendered:\n" + block);
        assertTrue(block.contains("#### Behavior"), "essential behavior rendered:\n" + block);
        assertTrue(block.contains("#### Security"), "security guidance is rendered:\n" + block);
        assertTrue(block.contains("#### Arguments"));
        assertTrue(block.contains("`absolutePath` (string, required)"));
        assertTrue(block.contains("`caseInsensitive` (boolean, optional)"));
        var nativeSchema = mapper.valueToTree(tool.inputSchema());
        assertEquals("string", nativeSchema.at("/properties/absolutePath/type").asText());
        assertEquals("boolean", nativeSchema.at("/properties/caseInsensitive/type").asText());
        assertEquals(
                "Absolute path to search under.",
                nativeSchema.at("/properties/absolutePath/description").asText());
        assertTrue(nativeSchema.path("required").toString().contains("absolutePath"));
        assertFalse(nativeSchema.path("required").toString().contains("caseInsensitive"));
        assertFalse(
                ToolDocs.documentationOf(ToolDocs.nonNullClass(GrepSearchTool.class))
                        .behavior()
                        .isBlank(),
                "grep_search has a typed @ToolDoc behavior section");
        assertTrue(
                ToolDocs.examplesOf(ToolDocs.nonNullClass(GrepSearchTool.class)).size() >= 3,
                "grep_search demonstrates its optional filters without duplicate examples");
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
