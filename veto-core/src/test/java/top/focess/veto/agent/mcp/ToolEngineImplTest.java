package top.focess.veto.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.NonNull;
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

    private @NonNull ToolEngineImpl newEngine() {
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
        ApplicationContext appCtx = mock(ToolDocs.nonNullClass(ApplicationContext.class));
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

    private static @NonNull ToolDefinition definition(
            @NonNull ToolEngineImpl engine, @NonNull String toolName) {
        ToolDefinition definition = engine.resolveDefinition(toolName);
        if (definition == null) throw new AssertionError("missing tool definition: " + toolName);
        return definition;
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
        assertInstanceOf(
                ToolDocs.nonNullClass(NativeToolDefinition.class), definition(engine, "view_file"));
    }

    @Test
    void executeNativeReadsFile(@TempDir @NonNull Path tempDir) throws Exception {
        ToolEngineImpl engine = newEngine();
        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "line one\nline two\n");

        ToolResult result =
                engine.execute(
                        new ToolCall("view_file", Map.of("absolutePath", file.toString()), "cid-1"),
                        definition(engine, "view_file"));
        assertTrue(result.success());
        assertTrue(result.content().contains("line one"));
    }

    @Test
    void filesystemToolsExposeOneCanonicalAbsolutePathParameter() {
        ToolEngineImpl engine = newEngine();

        Map<String, Class<?>> filesystemTools =
                Map.of(
                        "view_file", ToolDocs.nonNullClass(ViewFileTool.Args.class),
                        "list_dir", ToolDocs.nonNullClass(ListDirTool.Args.class),
                        "grep_search", ToolDocs.nonNullClass(GrepSearchTool.Args.class),
                        "write_to_file", ToolDocs.nonNullClass(WriteToFileTool.Args.class),
                        "replace_file_content",
                                ToolDocs.nonNullClass(ReplaceFileContentTool.Args.class));

        filesystemTools.forEach(
                (toolName, argsClass) -> {
                    var schema = ToolSchemaCompiler.compileFromRecord(argsClass);
                    assertTrue(
                            schema.path("properties").has("absolutePath"),
                            toolName
                                    + " must expose the canonical absolutePath parameter: "
                                    + schema);
                    assertTrue(
                            StreamSupport.stream(schema.path("required").spliterator(), false)
                                    .anyMatch(node -> "absolutePath".equals(node.asText())),
                            toolName + " must require absolutePath: " + schema);
                    assertFalse(
                            schema.path("properties").has("directoryPath"),
                            toolName + " must not expose directoryPath: " + schema);
                    assertFalse(
                            schema.path("properties").has("searchPath"),
                            toolName + " must not expose searchPath: " + schema);
                    assertFalse(
                            schema.path("properties").has("targetFile"),
                            toolName + " must not expose targetFile: " + schema);
                });
    }

    @Test
    void invalidNativeArgumentsNameUnknownAndMissingParametersWithoutRunningTool(
            @TempDir @NonNull Path tempDir) {
        ToolEngineImpl engine = newEngine();

        ToolResult result =
                engine.execute(
                        new ToolCall(
                                "list_dir", Map.of("wrongPath", tempDir.toString()), "cid-invalid"),
                        definition(engine, "list_dir"));

        assertFalse(result.success());
        assertTrue(result.content().contains("Invalid arguments for list_dir"), result.content());
        assertTrue(result.content().contains("unknown parameter 'wrongPath'"), result.content());
        assertTrue(
                result.content().contains("missing required parameter 'absolutePath'"),
                result.content());
        assertTrue(
                result.content().contains("Expected parameters: [absolutePath]"), result.content());
        assertFalse(result.content().contains("Tool execution failed: null"), result.content());
    }

    @Test
    void nullRequiredNativeArgumentIsRejectedBeforeDeserialization(@TempDir @NonNull Path tempDir)
            throws Exception {
        ToolEngineImpl engine = newEngine();
        ToolCall call =
                new ObjectMapper()
                        .readValue(
                                "{\"tool_name\":\"list_dir\",\"args\":{\"absolutePath\":null},"
                                        + "\"call_id\":\"cid-null\"}",
                                ToolDocs.nonNullClass(ToolCall.class));

        ToolResult result = engine.execute(call, definition(engine, "list_dir"));

        assertFalse(result.success());
        assertTrue(
                result.content().contains("missing required parameter 'absolutePath'"),
                result.content());
        assertFalse(result.content().contains("Tool execution failed: null"), result.content());
    }

    @Test
    void invalidNativeArgumentsReportNestedCommandFieldsBeforeProcessLaunch(
            @TempDir @NonNull Path tempDir) {
        ToolEngineImpl engine = newEngine();

        ToolResult result =
                engine.execute(
                        new ToolCall(
                                "run_command",
                                Map.of(
                                        "commands",
                                        List.of(Map.of("program", "java", "args", List.of())),
                                        "cwd",
                                        tempDir.toString(),
                                        "timeout",
                                        1),
                                "cid-invalid-command"),
                        definition(engine, "run_command"));

        assertFalse(result.success());
        assertTrue(
                result.content().contains("unknown parameter 'commands[0].program'"),
                result.content());
        assertTrue(
                result.content().contains("missing required parameter 'commands[0].executable'"),
                result.content());
    }

    @Test
    void executeNativeListDirUsesCanonicalAbsolutePath(@TempDir @NonNull Path tempDir)
            throws Exception {
        ToolEngineImpl engine = newEngine();
        Files.writeString(tempDir.resolve("visible.txt"), "content");

        ToolResult result =
                engine.execute(
                        new ToolCall(
                                "list_dir", Map.of("absolutePath", tempDir.toString()), "cid-list"),
                        definition(engine, "list_dir"));

        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("visible.txt"), result.content());
    }

    @Test
    void executeNativeListDirOnMissingPathReturnsErrorEnvelopeAndSuccessFalse(
            @TempDir @NonNull Path tempDir) {
        ToolEngineImpl engine = newEngine();
        // A path under tempDir that does not exist - simulating the agent's "dropped parent
        // segment" bug (e.g. asking for E:\minecraft\versions when versions only exists under
        // E:\minecraft\.minecraft\versions).
        Path missing = tempDir.resolve("nonexistent/child");

        ToolResult result =
                engine.execute(
                        new ToolCall(
                                "list_dir", Map.of("absolutePath", missing.toString()), "cid-x"),
                        definition(engine, "list_dir"));
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
            @TempDir @NonNull Path tempDir) {
        ToolEngineImpl engine = newEngine();
        Path missing = tempDir.resolve("does-not-exist.txt");

        ToolResult result =
                engine.execute(
                        new ToolCall(
                                "view_file", Map.of("absolutePath", missing.toString()), "cid-y"),
                        definition(engine, "view_file"));
        assertFalse(
                result.success(),
                "view_file on a missing path must also surface success=false via the same"
                        + " canonical error envelope - this is what was broken before the fix");
        assertTrue(result.content().contains("\"status\":\"error\""));
    }

    @Test
    void executeRunCommandRoutesThroughSubstrateNoShell(@TempDir @NonNull Path tempDir) {
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
                                        "STOP_ON_FAILURE",
                                        "timeout",
                                        30),
                                "cid-2"),
                        definition(engine, "run_command"));
        assertTrue(result.success(), "java -version should exit 0");
        assertNotNull(result.content());
    }

    @Test
    void runCommandWithoutTimeoutIsRejected(@TempDir @NonNull Path tempDir) {
        ToolEngineImpl engine = newEngine();
        ToolResult result =
                engine.execute(
                        new ToolCall(
                                "run_command",
                                Map.of(
                                        "commands",
                                        List.of(
                                                Map.of(
                                                        "executable",
                                                        "java",
                                                        "args",
                                                        List.of("-version"))),
                                        "cwd",
                                        tempDir.toString()),
                                "cid-no-timeout"),
                        definition(engine, "run_command"));
        assertFalse(result.success(), "run_command without an explicit timeout must fail");
        assertTrue(result.content().contains("timeout"), "error must name the missing timeout");
    }

    @Test
    void discoveredRemoteToolExecutesOverSseTransport() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/mcp",
                exchange -> {
                    String request =
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8);
                    String result =
                            request.contains("tools/list")
                                    ? "{\"tools\":[{\"name\":\"lookup_event\",\"description\":\"Look up an event\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"event_id\":{\"type\":\"string\"}},\"required\":[\"event_id\"]}}]}"
                                    : "{\"content\":[{\"type\":\"text\",\"text\":\"event found\"}],\"isError\":false}";
                    byte[] response =
                            ("data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}\n\n")
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();
        try {
            ToolEngineImpl engine = newEngine();
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
            var definitions =
                    engine.discoverAndRegister(new McpTransport.SseMcpTransport(endpoint, ""));

            assertEquals(1, definitions.size());
            ToolResult result =
                    engine.execute(
                            new ToolCall("lookup_event", Map.of("event_id", "event-1"), "remote-1"),
                            definition(engine, "lookup_event"));

            assertTrue(result.success(), result.content());
            assertEquals("event found", result.content());
        } finally {
            server.stop(0);
        }
    }
}
