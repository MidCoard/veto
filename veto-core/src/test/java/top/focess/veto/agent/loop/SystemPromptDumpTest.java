package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.identity.RoleToolFilter;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.mcp.ToolEngine;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.translation.CapabilityTranslator;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolDefinition;

/**
 * Diagnostic dump: compiles the FULL system prompt for each role/policy combination using the REAL
 * tool catalog (from {@link ToolEngine}), the REAL {@code default-system-prompt.md} template, and
 * the REAL resolved {@code VETO.md} law - then writes each rendered prompt to {@code
 * veto-core/build/prompt-dump/} for human inspection.
 *
 * <p>Run it on demand:
 *
 * <pre>{@code
 * ./gradlew.bat :veto-core:test --tests "top.focess.veto.agent.loop.SystemPromptDumpTest"
 * }</pre>
 *
 * <p>Then open the files under {@code veto-core/build/prompt-dump/}. This bypasses the running app
 * (no bootRun, no login) but uses the production prompt-assembly path ({@link PromptBlocks} +
 * {@link PromptTemplate}), so what you read is exactly what a real agent turn would receive.
 *
 * <p><b>Note:</b> each role's tools are resolved through the production {@link RoleToolFilter} (the
 * same filter {@code AgentService.buildPersona} applies), so the {@code ## Your Tools} block in
 * each role's dump reflects exactly what a real agent of that role would see - STANDALONE sees
 * everything except Leader-only arrangement tools; LEADER sees only investigation + arrangement;
 * MATE sees everything except all group tools.
 */
@SpringBootTest
@SuppressWarnings("initialization.field.uninitialized")
class SystemPromptDumpTest {

    private static final @NonNull Path DUMP_DIR = Path.of("build", "prompt-dump");

    @Autowired private @NonNull ToolEngine mcpEngine;
    @Autowired private @NonNull CapabilityTranslator translator;
    @Autowired private @NonNull Workspace workspace;
    @Autowired private @NonNull RoleToolFilter roleToolFilter;

    private final @NonNull SystemPromptResolver resolver = new SystemPromptResolver();
    private final @NonNull ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void dumpFullSystemPrompts() throws IOException {
        Files.createDirectories(DUMP_DIR);

        List<ToolDefinition> flatTools =
                translator.translateTools(
                        PromptCompiler.availableTools(mcpEngine.getActiveTools(null), true));
        String law = workspace.vetoMdResolver().resolve();
        String template = resolver.defaultPrompt();

        // Raw template, the full tool catalog (reference, pre-role-filter), and a plain inventory.
        // No labels or descriptors are prepended - each file is pure content, exactly what the
        // corresponding stage produces.
        write("00-template.md", template);
        String catalog = PromptBlocks.tools(flatTools);
        write("01-tool-catalog.md", catalog);
        write("02-tool-inventory.md", inventory(flatTools));
        writeJson("04-response-autonomous.json", translator.vetoResponseSchema(false, flatTools));
        writeJson("05-response-guided.json", translator.vetoResponseSchema(true, flatTools));

        // Full compiled prompt per role x policy, using the role-scoped tool set from
        // RoleToolFilter (the same path production takes via AgentService.buildPersona).
        DeployerPolicy[] policies = {DeployerPolicy.FULL_ACCESS, DeployerPolicy.SANDBOXED};
        Role[] roles = roles();
        for (Role role : roles) {
            List<ToolDefinition> roleTools =
                    translator.translateTools(
                            PromptCompiler.availableTools(roleToolFilter.resolve(role), true));
            for (DeployerPolicy policy : policies) {
                write(
                        role + "-" + policy + ".md",
                        render(role, policy, identityFor(role), roleTools, law, template));
            }
            // Per-role tool inventory so the role-scoping is visible at a glance.
            write("03-tools-" + role + ".md", inventory(roleTools));
        }

        int count = 5 + roles.length * (policies.length + 1);
        System.out.println(
                "=== System-prompt dump written to "
                        + DUMP_DIR.toAbsolutePath()
                        + " ("
                        + count
                        + " files) ===");
        System.out.println("Tools registered: " + flatTools.size());
        assertTrue(!flatTools.isEmpty(), "tool catalog is non-empty");
        assertTrue(
                toolNames(flatTools).contains("create_group"),
                "the production catalog must register the delegation entry tool");
        assertFalse(
                toolNames(flatTools).contains("load_skill"),
                "load_skill must not be exposed when the persona has no registered skills");
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
        assertFalse(
                template.contains("veto_pulse"),
                "internal response-schema names must not be exposed to the model");
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
        assertFalse(
                catalog.contains("available to YOU this turn"),
                "the persona capability catalog is not limited to one loop turn");
        assertTrue(
                catalog.contains("These are the tools available to YOU"),
                "the catalog must describe the active persona capabilities");
        assertFalse(
                catalog.contains("success=true"), "transport flags do not belong in tool prose");
        assertFalse(
                catalog.contains("#### Security"),
                "Gateway-enforced security detail must not bloat the agent catalog");
        assertTrue(
                catalog.length() < 60_000,
                "the model-visible tool catalog must stay concise; actual chars="
                        + catalog.length());
        for (ToolDefinition tool : flatTools) {
            String heading = "### `" + tool.name() + "`";
            int start = catalog.indexOf(heading);
            int end = catalog.indexOf("\n---\n", start);
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

    private @NonNull String render(
            @NonNull Role role,
            @NonNull DeployerPolicy policy,
            @NonNull String identity,
            @NonNull List<@NonNull ToolDefinition> tools,
            String law,
            @NonNull String template) {
        Map<String, String> blocks = new LinkedHashMap<>();
        blocks.put("LAW", PromptBlocks.law(law != null ? law : ""));
        blocks.put("IDENTITY", identity);
        blocks.put("ROLE", PromptBlocks.role(role));
        blocks.put("WORKSPACE", PromptBlocks.workspace(workspace));
        blocks.put("ENVIRONMENT", PromptBlocks.environment());
        blocks.put("RESULT_CONVENTIONS", tools.isEmpty() ? "" : PromptBlocks.resultConventions());
        blocks.put("TOOLS", PromptBlocks.tools(tools));
        blocks.put("BOUNDARIES", PromptBlocks.boundaries(policy));
        blocks.put("SKILLS", PromptBlocks.skills(List.of()));
        return PromptTemplate.render(template, blocks);
    }

    private @NonNull String identityFor(@NonNull Role role) {
        return switch (role) {
            case STANDALONE ->
                    PromptBlocks.identity(
                            "VetoCoreAgent",
                            "a standalone coding agent that plans and executes tasks directly.");
            case LEADER ->
                    PromptBlocks.identity(
                            "Leader",
                            "a Leader agent that decomposes a task into a DAG and arranges Mates to execute it.");
            case MATE ->
                    PromptBlocks.identity(
                            "Mate",
                            "a Mate worker agent that executes the task nodes dispatched to it.");
        };
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
                    case "grep_search" -> "Path not found: <path>";
                    case "list_dir" -> "Not a directory: <path>";
                    case "replace_file_content", "view_file" -> "Not a regular file: <path>";
                    case "stop_task", "view_task" -> "task not found: <taskId>";
                    case "web_search" -> "(no results)";
                    case "write_to_file" -> "File exists and overwrite=false: <path>";
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
