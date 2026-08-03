package top.focess.veto.agent.loop;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import top.focess.veto.agent.identity.Role;
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
 * <p><b>Note:</b> tools are the FULL registered set for every role today - {@link
 * ToolEngine#getActiveTools} is called with a {@code null} whitelist, matching current {@code
 * AgentService.buildPersona} behavior. That the {@code ## Your Tools} block is identical across
 * STANDALONE/LEADER/MATE is itself one of the issues this dump exists to surface (role-based tool
 * scoping is not yet implemented).
 */
@SpringBootTest
class SystemPromptDumpTest {

    private static final Path DUMP_DIR = Path.of("build", "prompt-dump");

    @Autowired private ToolEngine mcpEngine;
    @Autowired private CapabilityTranslator translator;
    @Autowired private Workspace workspace;

    private final SystemPromptResolver resolver = new SystemPromptResolver();

    @Test
    void dumpFullSystemPrompts() throws IOException {
        Files.createDirectories(DUMP_DIR);

        List<ToolDefinition> flatTools = translator.translateTools(mcpEngine.getActiveTools(null));
        String law = workspace.vetoMdResolver().resolve();
        String template = resolver.defaultPrompt();

        // Raw template, the tools block in isolation, and a plain inventory.
        write("00-template.md", "# Raw default-system-prompt.md template\n\n" + template);
        write(
                "01-tool-catalog.md",
                "# ## Your Tools block (all registered tools)\n\n" + PromptBlocks.tools(flatTools));
        write("02-tool-inventory.md", inventory(flatTools));

        // Full compiled prompt per role x policy.
        DeployerPolicy[] policies = {DeployerPolicy.FULL_ACCESS, DeployerPolicy.SANDBOXED};
        for (Role role : Role.values()) {
            for (DeployerPolicy policy : policies) {
                write(
                        role + "-" + policy + ".md",
                        render(role, policy, identityFor(role), flatTools, law, template));
            }
        }

        int count = 3 + Role.values().length * policies.length;
        System.out.println(
                "=== System-prompt dump written to "
                        + DUMP_DIR.toAbsolutePath()
                        + " ("
                        + count
                        + " files) ===");
        System.out.println("Tools registered: " + flatTools.size());
        assertTrue(!flatTools.isEmpty(), "tool catalog is non-empty");
    }

    private String render(
            Role role,
            DeployerPolicy policy,
            String identity,
            List<ToolDefinition> tools,
            String law,
            String template) {
        Map<String, String> blocks = new LinkedHashMap<>();
        blocks.put("LAW", PromptBlocks.law(law != null ? law : ""));
        blocks.put("IDENTITY", identity);
        blocks.put("ROLE", PromptBlocks.role(role));
        blocks.put("WORKSPACE", PromptBlocks.workspace(workspace));
        blocks.put("TOOLS", PromptBlocks.tools(tools));
        blocks.put("BOUNDARIES", PromptBlocks.boundaries(policy));
        blocks.put("SKILLS", PromptBlocks.skills(List.of()));
        return PromptTemplate.render(template, blocks);
    }

    private String identityFor(Role role) {
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

    private String inventory(List<ToolDefinition> tools) {
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

    private void write(String name, String content) throws IOException {
        Files.writeString(DUMP_DIR.resolve(name), content);
    }
}
