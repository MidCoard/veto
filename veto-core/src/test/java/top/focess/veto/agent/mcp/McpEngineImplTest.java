package top.focess.veto.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.mcp.tools.GrepSearchTool;
import top.focess.veto.agent.mcp.tools.ListDirTool;
import top.focess.veto.agent.mcp.tools.ReplaceFileContentTool;
import top.focess.veto.agent.mcp.tools.RunCommandTool;
import top.focess.veto.agent.mcp.tools.ViewFileTool;
import top.focess.veto.agent.mcp.tools.WriteToFileTool;
import top.focess.veto.agent.skills.SkillRegistry;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.sandbox.ConstrainedSubprocessSubstrate;
import top.focess.veto.sandbox.SandboxManager;

/**
 * Validates {@link McpEngineImpl}: manifest assembly, native dispatch via {@code executeFromJson},
 * {@code run_command} routing through the no-shell substrate, and agent-tool dispatch.
 */
class McpEngineImplTest {

    private McpEngineImpl newEngine() {
        ObjectMapper mapper =
                new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        List<NativeMcpTool<?>> tools =
                List.of(
                        new ViewFileTool(),
                        new ListDirTool(),
                        new WriteToFileTool(),
                        new ReplaceFileContentTool(),
                        new GrepSearchTool(),
                        new RunCommandTool());
        McpEngineImpl engine =
                new McpEngineImpl(
                        mapper,
                        tools,
                        new SandboxManager(new ConstrainedSubprocessSubstrate()),
                        new SkillRegistry(""));
        engine.init();
        return engine;
    }

    @Test
    void activeToolsIncludesNativesAndAlwaysOnAgents() {
        McpEngineImpl engine = newEngine();
        List<ToolDefinition> active = engine.getActiveTools(null);
        // 6 native + load_skill (create_group is now a native GroupTools bean, registered
        // separately
        // by Spring component-scan — not in this hand-built engine).
        assertEquals(7, active.size());
        assertTrue(active.stream().anyMatch(d -> d.name().equals("load_skill")));
    }

    @Test
    void whitelistFiltersNativesKeepsAgents() {
        McpEngineImpl engine = newEngine();
        List<ToolDefinition> active = engine.getActiveTools(Set.of("view_file"));
        // view_file (native, whitelisted) + load_skill (always-on agent)
        assertEquals(2, active.size());
    }

    @Test
    void resolveDefinitionDistinguishesFlavours() {
        McpEngineImpl engine = newEngine();
        assertInstanceOf(NativeToolDefinition.class, engine.resolveDefinition("view_file"));
        assertInstanceOf(AgentToolDefinition.class, engine.resolveDefinition("load_skill"));
    }

    @Test
    void executeNativeReadsFile(@TempDir Path tempDir) throws Exception {
        McpEngineImpl engine = newEngine();
        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "line one\nline two\n");

        McpToolResult result =
                engine.execute(
                        new ToolCall("view_file", Map.of("absolutePath", file.toString()), "cid-1"),
                        engine.resolveDefinition("view_file"));
        assertTrue(result.success());
        assertTrue(result.content().contains("line one"));
    }

    @Test
    void executeRunCommandRoutesThroughSubstrateNoShell(@TempDir Path tempDir) {
        McpEngineImpl engine = newEngine();
        // Use the JDK's own java binary (guaranteed present) — argv[] direct exec, no shell.
        String exe =
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        String javaBin = Path.of(System.getProperty("java.home"), "bin", exe).toString();
        McpToolResult result =
                engine.execute(
                        new ToolCall(
                                "run_command",
                                Map.of(
                                        "commands",
                                        List.of(
                                                Map.of(
                                                        "executable",
                                                        javaBin,
                                                        "args",
                                                        List.of("-version"))),
                                        "cwd",
                                        tempDir.toString(),
                                        "connect",
                                        "STOP_ON_FAILURE"),
                                "cid-2"),
                        engine.resolveDefinition("run_command"));
        assertTrue(result.success(), "java -version should exit 0");
        assertNotNull(result.content());
    }

    @Test
    void executeLoadSkillMissingReturnsError() {
        McpEngineImpl engine = newEngine();
        McpToolResult result =
                engine.execute(
                        new ToolCall("load_skill", Map.of("skillName", "does_not_exist"), "cid-3"),
                        engine.resolveDefinition("load_skill"));
        assertFalse(result.success());
        assertTrue(result.content().contains("not found"));
    }
}
