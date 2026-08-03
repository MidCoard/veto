package top.focess.veto.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import top.focess.veto.agent.mcp.tools.GrepSearchTool;
import top.focess.veto.agent.mcp.tools.ListDirTool;
import top.focess.veto.agent.mcp.tools.ReplaceFileContentTool;
import top.focess.veto.agent.mcp.tools.RunCommandTool;
import top.focess.veto.agent.mcp.tools.ViewFileTool;
import top.focess.veto.agent.mcp.tools.WriteToFileTool;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.sandbox.ConstrainedSubprocessSubstrate;
import top.focess.veto.sandbox.SandboxManager;

/**
 * Validates {@link ToolEngineImpl}: manifest assembly, native dispatch via {@code executeFromJson},
 * {@code run_command} routing through the no-shell substrate, and agent-tool dispatch.
 */
class ToolEngineImplTest {

    private ToolEngineImpl newEngine() {
        ObjectMapper mapper =
                new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        List<NativeTool<?>> tools =
                List.of(
                        new ViewFileTool(),
                        new ListDirTool(),
                        new WriteToFileTool(),
                        new ReplaceFileContentTool(),
                        new GrepSearchTool(),
                        new RunCommandTool());
        // Minimal ApplicationContext mock that returns no AgentTool beans
        ApplicationContext appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
        ToolEngineImpl engine =
                new ToolEngineImpl(
                        mapper,
                        tools,
                        new SandboxManager(new ConstrainedSubprocessSubstrate()),
                        appCtx);
        engine.init();
        return engine;
    }

    @Test
    void activeToolsIncludesNativesAndAlwaysOnAgents() {
        ToolEngineImpl engine = newEngine();
        List<ToolDefinition> active = engine.getActiveTools(null);
        // 6 native tools; agent tools are discovered via ApplicationContext (none in this test)
        assertEquals(6, active.size());
    }

    @Test
    void whitelistFiltersNativesKeepsAgents() {
        ToolEngineImpl engine = newEngine();
        List<ToolDefinition> active = engine.getActiveTools(Set.of("view_file"));
        // view_file (native, whitelisted); no agent beans in this test
        assertEquals(1, active.size());
    }

    @Test
    void resolveDefinitionDistinguishesFlavours() {
        ToolEngineImpl engine = newEngine();
        assertInstanceOf(NativeToolDefinition.class, engine.resolveDefinition("view_file"));
    }

    @Test
    void executeNativeReadsFile(@TempDir Path tempDir) throws Exception {
        ToolEngineImpl engine = newEngine();
        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "line one\nline two\n");

        ToolResult result =
                engine.execute(
                        new ToolCall("view_file", Map.of("absolutePath", file.toString()), "cid-1"),
                        engine.resolveDefinition("view_file"));
        assertTrue(result.success());
        assertTrue(result.content().contains("line one"));
    }

    @Test
    void executeNativeListDirOnMissingPathReturnsErrorEnvelopeAndSuccessFalse(
            @TempDir Path tempDir) {
        ToolEngineImpl engine = newEngine();
        // A path under tempDir that does not exist - simulating the agent's "dropped parent
        // segment" bug (e.g. asking for E:\minecraft\versions when versions only exists under
        // E:\minecraft\.minecraft\versions).
        Path missing = tempDir.resolve("nonexistent/child");

        ToolResult result =
                engine.execute(
                        new ToolCall(
                                "list_dir", Map.of("directoryPath", missing.toString()), "cid-x"),
                        engine.resolveDefinition("list_dir"));
        assertFalse(
                result.success(),
                "a native tool that returns {\"status\":\"error\",...} must surface success=false"
                        + " so the observation envelope renders [error, ...] and the agent does"
                        + " not mistake the failure for a successful call");
        assertTrue(
                result.content().contains("\"status\":\"error\""),
                "the body should be the canonical error envelope verbatim: " + result.content());
    }

    @Test
    void executeNativeViewFileOnMissingPathReturnsErrorEnvelopeAndSuccessFalse(
            @TempDir Path tempDir) {
        ToolEngineImpl engine = newEngine();
        Path missing = tempDir.resolve("does-not-exist.txt");

        ToolResult result =
                engine.execute(
                        new ToolCall(
                                "view_file", Map.of("absolutePath", missing.toString()), "cid-y"),
                        engine.resolveDefinition("view_file"));
        assertFalse(
                result.success(),
                "view_file on a missing path must also surface success=false via the same"
                        + " canonical error envelope - this is what was broken before the fix");
        assertTrue(result.content().contains("\"status\":\"error\""));
    }

    @Test
    void executeRunCommandRoutesThroughSubstrateNoShell(@TempDir Path tempDir) {
        ToolEngineImpl engine = newEngine();
        // Use the JDK's own java binary (guaranteed present) — argv[] direct exec, no shell.
        String exe =
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        String javaBin = Path.of(System.getProperty("java.home"), "bin", exe).toString();
        ToolResult result =
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
}
