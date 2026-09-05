package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * same filter {@code AgentService.buildPersona} applies), so the {@code ## Your Tools} block in
 * each role's dump reflects exactly what a real agent of that role would see - STANDALONE sees
 * execution/delegation capabilities; LEADER sees investigation + group control; MATE sees execution
 * capabilities without delegation or memory mutation.
 */
@SpringBootTest
@SuppressWarnings("initialization.field.uninitialized")
class SystemPromptDumpTest {

    private static final @NonNull Path DUMP_DIR = Path.of("build", "prompt-dump");
    private static final int MAX_TOOL_CATALOG_CHARS = 64 * 1024;

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
    void dumpFullSystemPrompts() throws IOException {
        Files.createDirectories(DUMP_DIR);

        List<ToolDefinition> registeredFlatTools =
                translator.translateTools(mcpEngine.getActiveTools(null));
        List<ToolDefinition> flatTools =
                translator.translateTools(
                        PromptCompiler.availableTools(mcpEngine.getActiveTools(null), true));
        Workspace renderedWorkspace = dumpWorkspace();
        String template = resolver.defaultPrompt();

        // Raw template, the full tool catalog (reference, pre-role-filter), and a plain inventory.
        // No labels or descriptors are prepended - each file is pure content, exactly what the
        // corresponding stage produces.
        write("00-template.md", template);
        String catalog = PromptBlocks.tools(flatTools);
        write("01-tool-catalog.md", catalog);
        write("02-tool-inventory.md", inventory(flatTools));
        write("02-registered-tool-inventory.md", inventory(registeredFlatTools));
        writeJson("04-response-autonomous.json", translator.vetoResponseSchema(false, flatTools));
        writeJson("05-response-guided.json", translator.vetoResponseSchema(true, flatTools));

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
                                    List.of(),
                                    false,
                                    1.0)
                            .systemMessage());
            // Per-role tool inventory so the role-scoping is visible at a glance.
            write("03-tools-" + role + ".md", inventory(roleTools));
        }
        var enabled =
                promptCompiler.compile(
                        personaFor(Role.STANDALONE), renderedWorkspace, null, List.of(), true, 1.0);
        var disabled =
                promptCompiler.compile(
                        personaFor(Role.STANDALONE),
                        renderedWorkspace,
                        null,
                        List.of(
                                TurnRecord.agentInit(
                                        0, "STANDALONE", enabled.systemMessage(), "test", "test")),
                        false,
                        1.0);
        write("STANDALONE-guided-enabled.md", enabled.systemMessage());
        write("STANDALONE-guided-disabled.md", disabled.systemMessage());
        assertTrue(enabled.systemMessage().contains("conditional_goto"));
        assertFalse(
                disabled.systemMessage().contains("conditional_goto"),
                "persisted enabled prompt must not restore a disabled capability");
        var enabledSchema = enabled.responseSchema();
        var disabledSchema = disabled.responseSchema();
        if (enabledSchema == null || disabledSchema == null)
            throw new AssertionError("schema missing");
        assertTrue(enabledSchema.path("properties").has("guide"));
        assertFalse(disabledSchema.path("properties").has("guide"));
        deleteLegacyRolePolicyDumps(roles);
        String standalone = Files.readString(DUMP_DIR.resolve("STANDALONE.md"));
        assertFalse(
                Pattern.compile("(?i)\\b(leader|mates?)\\b").matcher(standalone).find(),
                "Standalone instructions and its actual tool catalog must not describe other roles");
        assertTrue(standalone.contains("## Delegation Rules"));
        assertEquals(1, count(standalone, "## Delegation Rules"));
        assertTrue(standalone.contains("### Example: independent review areas"));
        for (Role role : roles) {
            String linked = Files.readString(DUMP_DIR.resolve(role + ".md"));
            boolean canDelegate =
                    roleToolFilter.resolve(role).stream()
                            .anyMatch(tool -> "create_group".equals(tool.name()));
            assertEquals(
                    canDelegate,
                    linked.contains("## Delegation Rules"),
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

        assertTrue(standalone.contains("## Operating Contract"));
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
        assertFalse(
                template.contains("veto_pulse"),
                "internal response-schema names must not be exposed to the model");
        assertFalse(
                template.contains("For a Mate"),
                "the shared response protocol must not contain role-specific behavior");
        assertFalse(
                objectMapper
                        .writeValueAsString(translator.vetoResponseSchema(false, flatTools))
                        .contains("parallel tool calls"),
                "the response schema must match ordered runtime execution");
        assertFalse(
                objectMapper
                        .writeValueAsString(translator.vetoResponseSchema(false, flatTools))
                        .contains("this turn's catalog"),
                "the response schema must describe the stable available catalog");
        assertFalse(
                objectMapper
                        .writeValueAsString(translator.vetoResponseSchema(false, flatTools))
                        .contains("User-facing text"),
                "the shared response schema must also be accurate for Mate internal reports");
        assertFalse(
                Files.readString(DUMP_DIR.resolve("LEADER.md"))
                        .contains("## Additional Role Guidance"),
                "default Leader guidance must not repeat the role contract");
        assertFalse(
                Files.readString(DUMP_DIR.resolve("MATE.md"))
                        .contains("## Additional Role Guidance"),
                "default Mate guidance must not repeat the role contract");
        assertFalse(
                Files.readString(DUMP_DIR.resolve("MATE.md")).contains("mate mate-sample"),
                "the Mate identity must not repeat its name and role");
        assertTrue(
                count(catalog, "\n### `") == flatTools.size(),
                "every registered tool has one catalog entry");
        assertTrue(
                count(catalog, "\n#### Args\n") == flatTools.size(),
                "every registered tool exposes an Args section");
        assertFalse(
                catalog.contains("error-special-plaintext"),
                "failure status must not be exposed as a content format");
        assertFalse(catalog.contains("veto_pulse"), "internal schema names must stay internal");
        for (String internalName :
                List.of(
                        "ToolEngine",
                        "SandboxSubstrate",
                        "ComSpec",
                        "CreateProcess",
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
                catalog.contains("These are the tools available to YOU"),
                "the catalog must describe the active persona capabilities");
        assertTrue(
                catalog.contains("`questions[].header` (string, required)"),
                "nested ask_user arguments must be explicit in the tool catalog");
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
        assertFalse(
                catalog.contains("#### Security"),
                "Gateway-enforced security detail must not bloat the agent catalog");
        assertTrue(
                catalog.length() < MAX_TOOL_CATALOG_CHARS,
                "the model-visible tool catalog must stay concise; actual chars="
                        + catalog.length());
        for (ToolDefinition tool : flatTools) {
            String heading = "### `" + tool.name() + "`";
            int start = catalog.indexOf(heading);
            int end = catalog.indexOf("\n### `", start + heading.length());
            String entry = end < 0 ? catalog.substring(start) : catalog.substring(start, end);
            assertEquals(
                    List.of(
                            "Args",
                            "Result formats",
                            "Behavior",
                            "When to use",
                            "When not to use",
                            "Call examples",
                            "Result contract",
                            "Result examples",
                            "Errors and edge cases"),
                    sectionHeadings(entry),
                    tool.name() + " must render the complete canonical contract order");
            assertKnownResultCasesAreUnique(tool.name(), entry);
        }
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
                    case "forget" -> "memory not found or not owned; nothing forgotten";
                    case "grep_search" -> "Search path does not exist: <absolutePath>";
                    case "list_dir" -> "Not a directory: <absolutePath>";
                    case "replace_file_content", "view_file" ->
                            "Not a regular file: <absolutePath>";
                    case "stop_task", "view_task" -> "task not found: <taskId>";
                    case "web_search" -> "(no results)";
                    case "write_to_file" -> "File exists and overwrite=false: <absolutePath>";
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
}
