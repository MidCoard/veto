package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import top.focess.veto.agent.TurnRecord;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.identity.RoleToolFilter;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolDefinition;
import top.focess.veto.llm.core.ToolResultPresentationMode;

/**
 * Diagnostic dump: compiles the full system prompt for each role under the active deployer policy
 * using the real tool catalog (from {@link ToolEngine}), the REAL {@code default-system-prompt.md}
 * template, and the REAL resolved {@code VETO.md} law - then writes each rendered prompt to {@code
 * veto-core/build/prompt-dump/} for human inspection.
 *
 * <p>Run it on demand:
 *
 * <pre>{@code
 * ./gradlew.bat :veto-core:test --tests "SystemPromptDumpTest"
 * }</pre>
 *
 * <p>Then open the files under {@code veto-core/build/prompt-dump/}. This bypasses the running app
 * (no bootRun, no login) but calls {@link PromptCompiler#compile}, including the configured
 * Leader/Mate prompt bases, so what you read follows the production prompt-assembly path.
 *
 * <p><b>Note:</b> each role's tools are resolved through the production {@link RoleToolFilter} (the
 * same filter {@code AgentService.buildPersona} applies), so the {@code ## Your tools} block in
 * each role's dump reflects exactly what a real agent of that role would see - STANDALONE sees
 * execution/delegation capabilities; LEADER sees investigation + group control; MATE sees execution
 * capabilities without delegation or memory mutation.
 */
@SpringBootTest(properties = "veto.context.model-input-tokens[test/test]=128000")
@SuppressWarnings("initialization.field.uninitialized")
class SystemPromptDumpTest {

    private static final @NonNull Path DUMP_DIR = Path.of("build", "prompt-dump");
    // Includes the two response submission tools, per-tool argument lists, security sections, and
    // up to five positionally paired argument/result examples per tool (actual catalog is ~105 KiB
    // at this writing).
    private static final int MAX_TOOL_CATALOG_CHARS = 113 * 1024;

    @Autowired private @NonNull ToolEngine mcpEngine;
    @Autowired private @NonNull CapabilityTranslator translator;
    @Autowired private @NonNull Workspace workspace;
    @Autowired private @NonNull RoleToolFilter roleToolFilter;
    @Autowired private @NonNull PromptCompiler promptCompiler;

    @Value("${veto.group.leader.system-prompt-base}")
    private @NonNull String leaderSystemPromptBase;

    @Value("${veto.group.mate.system-prompt-base}")
    private @NonNull String mateSystemPromptBase;

    private final @NonNull SystemPromptResolver resolver = new SystemPromptResolver();
    private final @NonNull ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void defaultTemplateContainsSourceOwnedComposition() {
        String source = PromptLibrary.source("default-system-prompt");
        assertTrue(source.contains("version: 2"));
        assertTrue(source.contains("@message system"));
        assertTrue(source.contains("@include tool-catalog"));
        assertFalse(source.contains("{{TOOLS}}"));
    }

    @Test
    void sharedStyleAndDiagramCapabilityApplyToEveryRole() {
        for (Role role : roles()) {
            String linked =
                    promptCompiler.linkSystemMessage(
                            personaFor(role),
                            dumpWorkspace(),
                            baseFor(role),
                            ToolResultPresentationMode.BASIC);
            assertTrue(linked.contains(PromptLibrary.text("answer-style")));
            assertTrue(
                    linked.contains(
                            "flowchart, sequenceDiagram, stateDiagram-v2, erDiagram, classDiagram"));
            assertTrue(linked.contains("ordinary text or Markdown"));
            assertFalse(linked.contains("one object matching the response schema"));
            assertFalse(linked.contains("presentation profile"));
            assertFalse(linked.contains("{{ANSWER_STYLE}}"));
            assertFalse(linked.contains("{{PRESENTATION_CAPABILITIES}}"));
        }
        String legacy = "Recorded original prompt, before presentation profiles.";
        var compiled =
                promptCompiler.compile(
                        personaFor(Role.STANDALONE),
                        dumpWorkspace(),
                        null,
                        List.of(TurnRecord.agentInit(1, "standalone", legacy, "test", "test")),
                        1.0);
        assertEquals(legacy, compiled.systemMessage());
        String baseline =
                promptCompiler.linkSystemMessage(
                        personaFor(Role.STANDALONE),
                        dumpWorkspace(),
                        null,
                        ToolResultPresentationMode.BASIC);
        assertTrue(baseline.contains(PromptLibrary.text("answer-style")));
        assertTrue(baseline.contains("## How to include diagrams"));
    }

    @Test
    void dumpFullSystemPrompts() throws IOException {
        Files.createDirectories(DUMP_DIR);

        List<ToolDefinition> registeredFlatTools =
                translator.translateTools(mcpEngine.getActiveTools(null));
        List<ToolDefinition> flatTools =
                translator.translateTools(
                        PromptCompiler.availableTools(mcpEngine.getActiveTools(null), true));
        Workspace renderedWorkspace = dumpWorkspace();
        String template = PromptLibrary.source("default-system-prompt");

        // Raw template, the full tool catalog (reference, pre-role-filter), and a plain inventory.
        // No labels or descriptors are prepended - each file is pure content, exactly what the
        // corresponding stage produces.
        write("00-template.md", template);
        String catalog = PromptBlocks.tools(flatTools);
        write("01-tool-catalog.md", catalog);
        write("02-tool-inventory.md", inventory(flatTools));
        write("02-registered-tool-inventory.md", inventory(registeredFlatTools));
        write(
                "03-native-tools.json",
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(flatTools));

        // Full compiled prompt per role, using the production compiler and configured role bases.
        Role[] roles = roles();
        for (Role role : roles) {
            List<ToolDefinition> roleTools =
                    translator.translateTools(
                            PromptCompiler.availableTools(roleToolFilter.resolve(role), true));
            write(
                    role + ".md",
                    promptCompiler
                            .compile(
                                    personaFor(role),
                                    renderedWorkspace,
                                    baseFor(role),
                                    List.of(
                                            TurnRecord.agentInit(
                                                    1,
                                                    role.name(),
                                                    promptCompiler.linkSystemMessage(
                                                            personaFor(role),
                                                            renderedWorkspace,
                                                            baseFor(role),
                                                            ToolResultPresentationMode.BASIC),
                                                    "test",
                                                    "test")),
                                    1.0)
                            .systemMessage());
            // Per-role tool inventory so the role-scoping is visible at a glance.
            write("03-tools-" + role + ".md", inventory(roleTools));
        }
        var planned =
                promptCompiler.compile(
                        personaFor(Role.STANDALONE),
                        renderedWorkspace,
                        null,
                        List.of(
                                TurnRecord.agentInit(
                                        1,
                                        "standalone",
                                        promptCompiler.linkSystemMessage(
                                                personaFor(Role.STANDALONE),
                                                renderedWorkspace,
                                                null,
                                                ToolResultPresentationMode.BASIC),
                                        "test",
                                        "test")),
                        1.0);
        write("STANDALONE-plan-tool.md", planned.systemMessage());
        assertTrue(planned.systemMessage().contains("conditional_goto"));
        assertNull(planned.responseSchema());
        assertTrue(planned.tools().stream().anyMatch(tool -> tool.name().equals("submit_plan")));
        deleteLegacyRolePolicyDumps(roles);
        String standalone = Files.readString(DUMP_DIR.resolve("STANDALONE.md"));
        assertFalse(
                Pattern.compile("\\b(Leader|Mate)s?\\b").matcher(standalone).find(),
                "Standalone instructions and its actual tool catalog must not describe other roles");
        assertTrue(standalone.contains("## How to delegate"));
        assertEquals(1, count(standalone, "## How to delegate"));
        assertTrue(standalone.contains("### Example: independent review areas"));
        for (Role role : roles) {
            String linked = Files.readString(DUMP_DIR.resolve(role + ".md"));
            boolean canDelegate =
                    roleToolFilter.resolve(role).stream()
                            .anyMatch(tool -> "create_group".equals(tool.name()));
            assertEquals(
                    canDelegate,
                    linked.contains("## How to delegate"),
                    "delegation guidance requires an available create_group tool: " + role);
            assertFalse(linked.contains("{{DELEGATION_RULES}}"));
            for (String internal :
                    List.of(
                            "Gateway",
                            "FILESYSTEM_PATH",
                            "CODE_CONTENT",
                            "WORKSPACE_WRITE",
                            "ELEVATED",
                            "NotScreened",
                            "Tool capability")) {
                assertFalse(linked.contains(internal), role + " prompt exposes " + internal);
            }
        }

        assertTrue(standalone.contains("## How to work"));
        assertFalse(standalone.contains("system/runtime contract"));
        assertTrue(
                Files.readString(DUMP_DIR.resolve("MATE.md"))
                        .contains(
                                "include the question in your final internal report to the Leader"));

        int count = 8 + roles.length * 2;
        System.out.println(
                "=== System-prompt dump written to "
                        + DUMP_DIR.toAbsolutePath()
                        + " ("
                        + count
                        + " files) ===");
        System.out.println("Tools registered: " + registeredFlatTools.size());
        System.out.println("Tools active without skills: " + flatTools.size());
        assertTrue(!flatTools.isEmpty(), "tool catalog is non-empty");
        assertTrue(
                toolNames(flatTools).contains("create_group"),
                "the production catalog must register the delegation entry tool");
        assertTrue(
                toolNames(flatTools).contains("recall_memory"),
                "the production catalog must expose the unified memory-recall tool");
        assertFalse(
                toolNames(flatTools).contains("recall_session"),
                "the removed session-only recall tool must not remain registered");
        assertFalse(
                toolNames(flatTools).contains("recall_insights"),
                "the removed insight-only recall tool must not remain registered");
        assertTrue(
                toolNames(flatTools).contains("write_memory"),
                "the production catalog must expose the consistently named memory-write tool");
        assertTrue(
                toolNames(flatTools).contains("forget_memory"),
                "the production catalog must expose the consistently named memory-delete tool");
        assertFalse(
                toolNames(flatTools).contains("write_insight"),
                "the replaced insight-specific write name must not remain registered");
        assertFalse(
                toolNames(flatTools).contains("forget"),
                "the replaced generic forget name must not remain registered");
        assertFalse(
                toolNames(flatTools).contains("load_skill"),
                "load_skill must not be exposed when the persona has no registered skills");
        assertTrue(
                toolNames(registeredFlatTools).contains("load_skill"),
                "the registered-tool inventory must retain conditional load_skill");
        assertTrue(
                mcpEngine.getActiveTools(null).stream()
                        .noneMatch(tool -> tool.capability() == ToolCapability.AGENT_CONTROL),
                "every registered agent tool must declare a specific capability");
        assertTrue(
                toolNames(
                                translator.translateTools(
                                        new ArrayList<>(roleToolFilter.resolve(Role.STANDALONE))))
                        .contains("create_group"),
                "STANDALONE must receive create_group");
        assertFalse(
                toolNames(
                                translator.translateTools(
                                        new ArrayList<>(roleToolFilter.resolve(Role.LEADER))))
                        .contains("create_group"),
                "LEADER must not receive create_group");
        assertFalse(
                toolNames(
                                translator.translateTools(
                                        new ArrayList<>(roleToolFilter.resolve(Role.MATE))))
                        .contains("create_group"),
                "MATE must not receive create_group");
        Set<String> leaderTools =
                toolNames(
                        translator.translateTools(
                                new ArrayList<>(roleToolFilter.resolve(Role.LEADER))));
        assertTrue(leaderTools.contains("inspect_group"), "LEADER must observe Mate outcomes");
        assertTrue(leaderTools.contains("post_message"), "LEADER must communicate with Mates");
        Set<String> mateTools =
                toolNames(
                        translator.translateTools(
                                new ArrayList<>(roleToolFilter.resolve(Role.MATE))));
        assertFalse(mateTools.contains("forget_memory"), "MATE cannot delete user memory");
        assertFalse(mateTools.contains("write_memory"), "MATE cannot mutate user memory");
        assertFalse(
                Files.readString(DUMP_DIR.resolve("LEADER.md")).contains("execute in parallel"),
                "prompt must match ordered runtime tool execution");
        String sharedInstructions =
                PromptLibrary.text(
                        "response-protocol", Map.of("toolNames", List.of("answer_with_citations")));
        assertFalse(
                sharedInstructions.contains("veto_pulse"),
                "internal response-schema names must not be exposed to the model");
        assertFalse(
                sharedInstructions.contains("For a Mate"),
                "the shared response protocol must not contain role-specific behavior");
        assertFalse(
                Files.readString(DUMP_DIR.resolve("LEADER.md"))
                        .contains("## Additional role guidance"),
                "default Leader guidance must not repeat the role contract");
        assertFalse(
                Files.readString(DUMP_DIR.resolve("MATE.md"))
                        .contains("## Additional role guidance"),
                "default Mate guidance must not repeat the role contract");
        assertFalse(
                Files.readString(DUMP_DIR.resolve("MATE.md")).contains("mate mate-sample"),
                "the Mate identity must not repeat its name and role");
        assertTrue(
                count(catalog, "\n### `") == flatTools.size(),
                "every registered tool has one catalog entry");
        assertTrue(
                catalog.contains("\n#### Arguments\n"),
                "the catalogue renders the argument list from each tool's native schema");
        assertFalse(
                catalog.contains("error-special-plaintext"),
                "failure status must not be exposed as a content format");
        assertFalse(catalog.contains("veto_pulse"), "internal schema names must stay internal");
        for (String internalName :
                List.of(
                        "ToolEngine",
                        "SandboxSubstrate",
                        "ComSpec",
                        "BackgroundTaskManager",
                        "SandboxProfile",
                        "AGENT_INIT",
                        "deployer's Leader-tier",
                        "FULL_ACCESS",
                        "Gateway",
                        "FILESYSTEM_PATH",
                        "CODE_CONTENT",
                        "WORKSPACE_WRITE",
                        "ELEVATED",
                        "NotScreened",
                        "Tool capability",
                        "SANDBOXED",
                        "TENANT",
                        "Path mode:",
                        "Under PROTECTED",
                        "Policy: PROTECTED")) {
            for (ToolDefinition tool : flatTools) {
                assertFalse(
                        tool.documentation().security().contains(internalName),
                        tool.name() + " security guidance must not expose " + internalName);
            }
            assertFalse(
                    catalog.contains(internalName),
                    "model-facing tool documentation must not expose " + internalName);
        }
        // "CreateProcess" stays banned from authored prose; verbatim failure examples may quote
        // real sandbox stderr (run_command's observed CreateProcessW spawn failure).
        for (ToolDefinition tool : flatTools) {
            assertFalse(
                    tool.documentation().security().contains("CreateProcess"),
                    tool.name() + " security guidance must not expose CreateProcess");
        }
        assertFalse(
                catalog.contains("available to YOU this turn"),
                "the persona capability catalog is not limited to one loop turn");
        for (String redundantMetaExplanation :
                List.of(
                        "not a call argument",
                        "not call arguments",
                        "no id argument",
                        "cannot be overridden by the call",
                        "cannot be supplied by the call",
                        "managed by Veto",
                        "sandbox-profile")) {
            assertFalse(
                    catalog.contains(redundantMetaExplanation),
                    "tool documentation must state behavior directly instead of explaining an"
                            + " absent argument: "
                            + redundantMetaExplanation);
        }
        assertTrue(
                catalog.contains("The arguments of each tool are listed from its native schema"),
                "the catalog must describe the active persona capabilities");
        var questionTool =
                flatTools.stream()
                        .filter(t -> t.name().equals("ask_user"))
                        .findFirst()
                        .orElseThrow();
        var questionSchema =
                new ObjectMapper()
                        .valueToTree(questionTool.inputSchema())
                        .path("properties")
                        .path("questions")
                        .path("items");
        assertEquals(
                "string", questionSchema.path("properties").path("header").path("type").asText());
        assertTrue(
                questionSchema.path("required").toString().contains("\"header\""),
                "nested ask_user arguments must remain explicit in its native schema");
        assertTrue(
                catalog.contains("RESULT_LIMIT, VISIT_LIMIT, TIME_LIMIT, or OUTPUT_LIMIT"),
                "find_files must enumerate its truncation reasons");
        assertTrue(
                catalog.contains("queued result means accepted by the bounded input queue"),
                "input_task must distinguish queueing from process consumption");
        assertTrue(
                catalog.contains("`file`, `directory`, or `symbolic_link`"),
                "path tools must declare their stable kind values");
        assertTrue(
                catalog.contains("cancelled by a backend restart"),
                "ask_user must not overclaim durable restart recovery");
        assertFalse(
                catalog.contains("success=true"), "transport flags do not belong in tool prose");
        assertTrue(
                catalog.contains("#### Security"),
                "each tool's declared security guidance closes its catalog entry");
        assertTrue(
                catalog.length() < MAX_TOOL_CATALOG_CHARS,
                "the model-visible tool catalog must stay concise; actual chars="
                        + catalog.length());
        for (ToolDefinition tool : flatTools) {
            String heading = "### `" + tool.name() + "`";
            int start = catalog.indexOf(heading);
            int end = catalog.indexOf("\n### `", start + heading.length());
            String entry = end < 0 ? catalog.substring(start) : catalog.substring(start, end);
            if (tool.name().startsWith("plugin_")) {
                // Plugin tools are third-party contributions: the catalogue renders their
                // description, arguments, and result formats; Veto-owned documentation sections
                // exist only for Veto-owned tools.
                assertTrue(
                        entry.contains("#### Arguments") && entry.contains("#### Result formats"),
                        tool.name() + " must render its plugin descriptor contract");
                continue;
            }
            assertEquals(
                    List.of(
                            "Arguments",
                            "Result formats",
                            "Behavior",
                            "When to use",
                            "When not to use",
                            "Argument examples",
                            "Result contract",
                            "Result examples",
                            "Errors and edge cases",
                            "Security"),
                    sectionHeadings(entry),
                    tool.name() + " must render the complete canonical contract order");
            assertKnownResultCasesAreUnique(tool.name(), entry);
        }
    }

    /**
     * Size report: compiles the standard matrix (each role x each {@link
     * ToolResultPresentationMode}) plus the tool-agent and web-reader entries, then reports total
     * chars, a rough token estimate (chars / 4), and the size of the {@code ## Your tools}
     * catalogue section when present. The table is printed to the test log and written as UTF-8 to
     * {@code veto-core/build/reports/prompts/prompt-sizes.txt}. Rows are ordered by role, then
     * mode, so consecutive reports are diffable.
     */
    @Test
    void reportPromptSizes() throws IOException {
        Path reportPath = Path.of("build", "reports", "prompts", "prompt-sizes.txt");
        Path reportDir = reportPath.getParent();
        if (reportDir == null) throw new AssertionError("report path has no parent directory");
        Files.createDirectories(reportDir);

        Workspace renderedWorkspace = dumpWorkspace();
        List<String> rows = new ArrayList<>();
        for (Role role : roles()) {
            AgentPersona persona = personaFor(role);
            String base = baseFor(role);
            for (ToolResultPresentationMode mode : presentationModes()) {
                String linked =
                        promptCompiler.linkSystemMessage(persona, renderedWorkspace, base, mode);
                String compiled =
                        promptCompiler
                                .compile(
                                        persona,
                                        renderedWorkspace,
                                        base,
                                        List.of(
                                                TurnRecord.agentInit(
                                                        1, role.name(), linked, "test", "test")),
                                        1.0)
                                .systemMessage();
                rows.add(sizeRow(role.name(), mode.name(), compiled));
            }
        }
        PromptCompiler toolAgentCompiler =
                PromptCompiler.isolated(
                        translator,
                        objectMapper,
                        "Fetch the requested page and answer the objective.",
                        32 * 1024);
        rows.add(
                sizeRow(
                        "default-tool-agent-system-prompt",
                        ToolResultPresentationMode.BASIC.name(),
                        toolAgentCompiler.linkSystemMessage(
                                personaFor(Role.STANDALONE),
                                renderedWorkspace,
                                null,
                                ToolResultPresentationMode.BASIC)));
        rows.add(
                sizeRow(
                        "web-fetch-system-prompt",
                        "-",
                        PromptLibrary.text("web-fetch-system-prompt")));

        StringBuilder report = new StringBuilder();
        report.append("# Prompt size report\n");
        report.append(
                "# chars: total characters; ~tokens: chars / 4; catalog: chars of the `## Your"
                        + " tools` section (- when absent)\n");
        report.append(
                String.format(
                        "%-34s %-9s %10s %10s %10s\n",
                        "prompt", "mode", "chars", "~tokens", "catalog"));
        for (String row : rows) {
            report.append(row).append('\n');
        }
        String text = report.toString();
        System.out.println(text);
        Files.writeString(reportPath, text, StandardCharsets.UTF_8);
        assertFalse(rows.isEmpty(), "prompt size report must contain at least one row");
    }

    private static @NonNull String sizeRow(
            @NonNull String prompt, @NonNull String mode, @NonNull String content) {
        int catalogChars = toolCatalogChars(content);
        return String.format(
                "%-34s %-9s %10d %10d %10s",
                prompt,
                mode,
                content.length(),
                content.length() / 4,
                catalogChars < 0 ? "-" : Integer.toString(catalogChars));
    }

    /** Chars of the {@code ## Your tools} block through the next {@code ## } heading or EOF. */
    private static int toolCatalogChars(@NonNull String prompt) {
        int start = prompt.indexOf("## Your tools");
        if (start < 0) return -1;
        int end = prompt.indexOf("\n## ", start + "## Your tools".length());
        return (end < 0 ? prompt.length() : end) - start;
    }

    private @NonNull Workspace dumpWorkspace() {
        String roots = System.getenv("VETO_PROMPT_DUMP_WORKSPACE_ROOTS");
        if (roots == null || roots.isBlank()) {
            return workspace;
        }
        String indexValue = System.getenv("VETO_PROMPT_DUMP_CURRENT_ROOT_INDEX");
        int currentRootIndex =
                indexValue == null || indexValue.isBlank() ? 0 : Integer.parseInt(indexValue);
        return Workspace.fromConfig("", roots, workspace.pathMode().name(), currentRootIndex);
    }

    private @NonNull AgentPersona personaFor(@NonNull Role role) {
        Set<top.focess.veto.agent.tool.ToolDefinition> tools = roleToolFilter.resolve(role);
        return switch (role) {
            case STANDALONE ->
                    new AgentPersona(
                            "dump-standalone",
                            "VetoCoreAgent",
                            "a standalone coding agent that plans and executes tasks directly.",
                            tools,
                            List.of(),
                            role);
            case LEADER ->
                    new AgentPersona(
                            "dump-leader",
                            "VetoCoreAgent",
                            "a standalone coding agent transformed into the Leader of this"
                                    + " delegation.",
                            tools,
                            List.of(),
                            role);
            case MATE ->
                    new AgentPersona(
                            "dump-mate",
                            "mate-sample",
                            "a Mate assigned to the coding skillset",
                            tools,
                            List.of(),
                            role);
        };
    }

    private String baseFor(@NonNull Role role) {
        return switch (role) {
            case STANDALONE -> null;
            case LEADER -> leaderSystemPromptBase;
            case MATE -> mateSystemPromptBase;
        };
    }

    private static void deleteLegacyRolePolicyDumps(@NonNull Role @NonNull [] roles)
            throws IOException {
        for (Role role : roles) {
            Files.deleteIfExists(DUMP_DIR.resolve(role + "-FULL_ACCESS.md"));
            Files.deleteIfExists(DUMP_DIR.resolve(role + "-SANDBOXED.md"));
        }
    }

    private @NonNull String inventory(@NonNull List<@NonNull ToolDefinition> tools) {
        StringBuilder sb =
                new StringBuilder("# Tool Inventory (").append(tools.size()).append(")\n\n");
        for (ToolDefinition t : tools) {
            sb.append("- `").append(t.name()).append("` - ").append(t.description());
            if (!t.examples().isEmpty()) {
                sb.append(" (").append(t.examples().size()).append(" examples)");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private void write(@NonNull String name, @NonNull String content) throws IOException {
        Files.writeString(DUMP_DIR.resolve(name), content);
    }

    private void writeJson(@NonNull String name, @NonNull Object value) throws IOException {
        write(name, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    private static int count(@NonNull String text, @NonNull String token) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(token, from)) >= 0) {
            count++;
            from += token.length();
        }
        return count;
    }

    private static @NonNull List<@NonNull String> sectionHeadings(@NonNull String entry) {
        return entry.lines()
                .filter(line -> line.startsWith("#### "))
                .map(line -> line.substring("#### ".length()).strip())
                .toList();
    }

    private static void assertKnownResultCasesAreUnique(
            @NonNull String toolName, @NonNull String entry) {
        String diagnostic =
                switch (toolName) {
                    case "forget" ->
                            "Memory not found: the memory does not exist or is not owned; nothing forgotten.";
                    case "grep_search" -> "Path not found: <absolutePath>";
                    case "list_dir" -> "Not a directory: <absolutePath>";
                    case "replace_file_content", "view_file" ->
                            "Not a regular file: <absolutePath>";
                    case "stop_task", "view_task" -> "Task not found: <taskId>";
                    case "web_search" -> "(no results)";
                    case "write_to_file" ->
                            "Already exists: <absolutePath> exists and overwrite is false.";
                    default -> null;
                };
        if (diagnostic != null) {
            assertEquals(
                    1,
                    count(entry, diagnostic),
                    toolName + " must define the result case once, in Result contract");
        }
    }

    private static @NonNull Set<String> toolNames(@NonNull List<@NonNull ToolDefinition> tools) {
        return tools.stream().map(ToolDefinition::name).collect(Collectors.toUnmodifiableSet());
    }

    private static @NonNull Role @NonNull [] roles() {
        Role[] roles = Role.values();
        if (roles == null) throw new AssertionError("Role.values returned null");
        return roles;
    }

    private static @NonNull ToolResultPresentationMode @NonNull [] presentationModes() {
        ToolResultPresentationMode[] modes = ToolResultPresentationMode.values();
        if (modes == null)
            throw new AssertionError("ToolResultPresentationMode.values returned null");
        return modes;
    }
}
