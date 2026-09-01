package top.focess.veto.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.mcp.tools.GrepSearchTool;
import top.focess.veto.agent.mcp.tools.ListDirTool;
import top.focess.veto.agent.mcp.tools.ReplaceFileContentTool;
import top.focess.veto.agent.mcp.tools.RunCommandTool;
import top.focess.veto.agent.mcp.tools.RunTaskTool;
import top.focess.veto.agent.mcp.tools.StopTaskTool;
import top.focess.veto.agent.mcp.tools.ViewFileTool;
import top.focess.veto.agent.mcp.tools.ViewTaskTool;
import top.focess.veto.agent.mcp.tools.WriteToFileTool;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.sandbox.SandboxManager;
import top.focess.veto.sandbox.TestSandboxFactory;

/**
 * Validates {@link ToolEngineImpl}: manifest assembly, native dispatch via {@code executeFromJson},
 * {@code run_command} routing through the no-shell substrate, and agent-tool dispatch.
 */
class ToolEngineImplTest {

    @ToolDoc(
            description = "Fails for protocol testing.",
            resultFormats = {ToolResultFormat.PLAINTEXT},
            behavior = "Always reports the requested protocol failure.",
            whenToUse = "Use in protocol failure tests.",
            whenNotToUse = "Do not use outside tests.",
            resultContract = "Plain text.",
            errorsAndEdgeCases = "The supplied reason is returned as a failure.",
            security = "Test-only agent tool.",
            examples = {"{\"reason\":\"bad input\"}"},
            returnExamples = {"ok"})
    private record FailingAgentArgs(@NonNull String reason) {}

    private static final class FailingAgentTool implements AgentTool<FailingAgentArgs> {

        private boolean executed;

        @Override
        public @NonNull String getName() {
            return "failing_agent";
        }

        @Override
        public @NonNull String getDescription() {
            return "Fails for protocol testing.";
        }

        @Override
        public @NonNull Class<FailingAgentArgs> getArgsClass() {
            return ToolDocs.nonNullClass(FailingAgentArgs.class);
        }

        @Override
        public @NonNull String execute(@NonNull FailingAgentArgs args) {
            executed = true;
            return ToolErrors.failure(args.reason());
        }

        boolean executed() {
            return executed;
        }
    }

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
                        new SandboxManager(TestSandboxFactory.uncontainedSubprocesses()),
                        appCtx);
        engine.init();
        return engine;
    }

    private @NonNull WindowsSandboxFixture newWindowsSandboxFixture() {
        ObjectMapper mapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SandboxManager sandboxes = new SandboxManager(TestSandboxFactory.platformSandbox());
        BackgroundTaskManager backgroundTasks = new BackgroundTaskManager(sandboxes);
        List<NativeTool<?>> tools =
                List.of(
                        new RunCommandTool(),
                        new RunTaskTool(backgroundTasks, mapper),
                        new ViewTaskTool(backgroundTasks, mapper),
                        new StopTaskTool(backgroundTasks, mapper));
        ApplicationContext appCtx = mock(ToolDocs.nonNullClass(ApplicationContext.class));
        when(appCtx.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
        ToolEngineImpl engine = new ToolEngineImpl(mapper, tools, sandboxes, appCtx);
        engine.init();
        return new WindowsSandboxFixture(engine, backgroundTasks, mapper);
    }

    private record WindowsSandboxFixture(
            @NonNull ToolEngineImpl engine,
            @NonNull BackgroundTaskManager backgroundTasks,
            @NonNull ObjectMapper mapper) {}

    private static @NonNull ToolDefinition definition(
            @NonNull ToolEngineImpl engine, @NonNull String toolName) {
        ToolDefinition definition = engine.resolveDefinition(toolName);
        if (definition == null) throw new AssertionError("missing tool definition: " + toolName);
        return definition;
    }

    private static @NonNull ToolResult executeAuthorized(
            @NonNull ToolEngineImpl engine,
            @NonNull ToolCall call,
            @NonNull ToolDefinition definition,
            @NonNull Path workspaceRoot) {
        ToolExecutionPermit permit =
                ToolExecutionPermit.capture(
                        call, definition, Workspace.single(workspaceRoot, PathMode.REAL));
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "test-agent",
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        ToolResultPresentationMode.BASIC,
                        permit));
        try {
            return engine.execute(call, definition);
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    @Test
    void activeToolsIncludesNativesAndAlwaysOnAgents() {
        ToolEngineImpl engine = newEngine();
        List<ToolDefinition> active = engine.getActiveTools(null);
        // 6 native tools; agent tools are discovered via ApplicationContext (none in this test)
        assertEquals(6, active.size());
    }

    @Test
    void agentFailureUsesPlainDiagnosticAndSuccessFalse() {
        ObjectMapper mapper =
                new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        ApplicationContext appCtx = mock(ToolDocs.nonNullClass(ApplicationContext.class));
        when(appCtx.getBeansOfType(AgentTool.class))
                .thenReturn(Map.of("failingAgentTool", new FailingAgentTool()));
        ToolEngineImpl engine =
                new ToolEngineImpl(
                        mapper,
                        List.of(),
                        new SandboxManager(TestSandboxFactory.uncontainedSubprocesses()),
                        appCtx);
        engine.init();

        ToolResult result =
                engine.execute(
                        new ToolCall(
                                "failing_agent", Map.of("reason", "bad input"), "cid-agent-error"),
                        definition(engine, "failing_agent"));

        assertFalse(result.success());
        assertEquals("bad input", result.content());
        assertFalse(result.content().stripLeading().startsWith("{"));
    }

    @Test
    void missingRequiredAgentArgumentIsRejectedBeforeHandlerExecution() {
        ObjectMapper mapper =
                new ObjectMapper()
                        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        FailingAgentTool tool = new FailingAgentTool();
        ApplicationContext appCtx = mock(ToolDocs.nonNullClass(ApplicationContext.class));
        when(appCtx.getBeansOfType(AgentTool.class)).thenReturn(Map.of("failingAgentTool", tool));
        ToolEngineImpl engine =
                new ToolEngineImpl(
                        mapper,
                        List.of(),
                        new SandboxManager(TestSandboxFactory.uncontainedSubprocesses()),
                        appCtx);
        engine.init();

        ToolResult result =
                engine.execute(
                        new ToolCall("failing_agent", Map.of(), "cid-agent-missing"),
                        definition(engine, "failing_agent"));

        assertFalse(result.success());
        assertTrue(
                result.content().contains("missing required parameter 'reason'"), result.content());
        assertFalse(tool.executed(), "agent handler must not run for invalid arguments");
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

        ToolDefinition definition = definition(engine, "view_file");
        ToolResult result =
                executeAuthorized(
                        engine,
                        new ToolCall("view_file", Map.of("absolutePath", file.toString()), "cid-1"),
                        definition,
                        tempDir);
        assertTrue(result.success());
        assertTrue(result.content().contains("line one"));
    }

    @Test
    void filesystemExecutionWithoutGatewayPermitFailsClosed(@TempDir @NonNull Path tempDir)
            throws Exception {
        ToolEngineImpl engine = newEngine();
        Path file = tempDir.resolve("unapproved.txt");
        Files.writeString(file, "must not be read");

        ToolResult result =
                engine.execute(
                        new ToolCall(
                                "view_file", Map.of("absolutePath", file.toString()), "cid-none"),
                        definition(engine, "view_file"));

        assertFalse(result.success(), result.content());
        assertTrue(result.content().contains("authorized execution context"), result.content());
        assertFalse(result.content().contains("must not be read"), result.content());
    }

    @Test
    void filesystemExecutionRejectsArgumentsChangedAfterScreening(@TempDir @NonNull Path tempDir)
            throws Exception {
        ToolEngineImpl engine = newEngine();
        Path approved = tempDir.resolve("approved.txt");
        Path changed = tempDir.resolve("changed.txt");
        Files.writeString(approved, "approved content");
        Files.writeString(changed, "changed content");
        ToolDefinition definition = definition(engine, "view_file");
        ToolCall screened =
                new ToolCall(
                        "view_file", Map.of("absolutePath", approved.toString()), "cid-screened");
        ToolExecutionPermit permit =
                ToolExecutionPermit.capture(
                        screened, definition, Workspace.single(tempDir, PathMode.REAL));
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "test-agent",
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        ToolResultPresentationMode.BASIC,
                        permit));
        try {
            ToolResult result =
                    engine.execute(
                            new ToolCall(
                                    "view_file",
                                    Map.of("absolutePath", changed.toString()),
                                    "cid-changed"),
                            definition);
            assertFalse(result.success(), result.content());
            assertTrue(result.content().contains("do not match the screened"), result.content());
            assertFalse(result.content().contains("changed content"), result.content());
        } finally {
            ToolCallContextHolder.clear();
        }
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

        ToolDefinition definition = definition(engine, "list_dir");
        ToolResult result =
                executeAuthorized(
                        engine,
                        new ToolCall(
                                "list_dir", Map.of("absolutePath", tempDir.toString()), "cid-list"),
                        definition,
                        tempDir);

        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("visible.txt"), result.content());
    }

    @Test
    void grepSearchAppliesIncludeGlobs(@TempDir @NonNull Path tempDir) throws Exception {
        ToolEngineImpl engine = newEngine();
        Files.writeString(tempDir.resolve("Included.java"), "needle\n");
        Files.writeString(tempDir.resolve("excluded.txt"), "needle\n");
        ToolDefinition definition = definition(engine, "grep_search");

        ToolResult result =
                executeAuthorized(
                        engine,
                        new ToolCall(
                                "grep_search",
                                Map.of(
                                        "absolutePath",
                                        tempDir.toString(),
                                        "query",
                                        "needle",
                                        "includes",
                                        List.of("*.java")),
                                "cid-grep"),
                        definition,
                        tempDir);

        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("Included.java"), result.content());
        assertFalse(result.content().contains("excluded.txt"), result.content());
    }

    @Test
    void replaceFileContentIsBoundToTheApprovedLineRange(@TempDir @NonNull Path tempDir)
            throws Exception {
        ToolEngineImpl engine = newEngine();
        Path file = tempDir.resolve("range.txt");
        Files.writeString(file, "same\nkeep\nsame\n");
        ToolDefinition definition = definition(engine, "replace_file_content");

        ToolResult result =
                executeAuthorized(
                        engine,
                        new ToolCall(
                                "replace_file_content",
                                Map.of(
                                        "absolutePath",
                                        file.toString(),
                                        "startLine",
                                        3,
                                        "endLine",
                                        3,
                                        "targetContent",
                                        "same",
                                        "replacementContent",
                                        "changed"),
                                "cid-range"),
                        definition,
                        tempDir);

        assertTrue(result.success(), result.content());
        assertEquals("same\nkeep\nchanged\n", Files.readString(file));
    }

    @Test
    void replaceFileContentRefusesTargetOutsideSelectedRange(@TempDir @NonNull Path tempDir)
            throws Exception {
        ToolEngineImpl engine = newEngine();
        Path file = tempDir.resolve("outside.txt");
        Files.writeString(file, "target\nkeep\n");
        ToolDefinition definition = definition(engine, "replace_file_content");

        ToolResult result =
                executeAuthorized(
                        engine,
                        new ToolCall(
                                "replace_file_content",
                                Map.of(
                                        "absolutePath",
                                        file.toString(),
                                        "startLine",
                                        2,
                                        "endLine",
                                        2,
                                        "targetContent",
                                        "target",
                                        "replacementContent",
                                        "changed"),
                                "cid-outside"),
                        definition,
                        tempDir);

        assertFalse(result.success(), result.content());
        assertEquals("target\nkeep\n", Files.readString(file));
    }

    @Test
    void executeNativeListDirOnMissingPathReturnsSpecialPlaintextAndSuccessFalse(
            @TempDir @NonNull Path tempDir) {
        ToolEngineImpl engine = newEngine();
        // A path under tempDir that does not exist - simulating the agent's "dropped parent
        // segment" bug (e.g. asking for E:\minecraft\versions when versions only exists under
        // E:\minecraft\.minecraft\versions).
        Path missing = tempDir.resolve("nonexistent/child");

        ToolDefinition definition = definition(engine, "list_dir");
        ToolResult result =
                executeAuthorized(
                        engine,
                        new ToolCall(
                                "list_dir", Map.of("absolutePath", missing.toString()), "cid-x"),
                        definition,
                        tempDir);
        assertFalse(
                result.success(),
                "a native tool failure must use the structural failure channel so the agent does"
                        + " not mistake it for a successful call");
        assertTrue(
                result.content().contains("Not a directory"),
                "the body should be a plain actionable diagnostic: " + result.content());
        assertFalse(result.content().stripLeading().startsWith("{"), result.content());
    }

    @Test
    void executeNativeViewFileOnMissingPathReturnsSpecialPlaintextAndSuccessFalse(
            @TempDir @NonNull Path tempDir) {
        ToolEngineImpl engine = newEngine();
        Path missing = tempDir.resolve("does-not-exist.txt");

        ToolDefinition definition = definition(engine, "view_file");
        ToolResult result =
                executeAuthorized(
                        engine,
                        new ToolCall(
                                "view_file", Map.of("absolutePath", missing.toString()), "cid-y"),
                        definition,
                        tempDir);
        assertFalse(
                result.success(),
                "view_file on a missing path must surface success=false through the dedicated"
                        + " failure channel");
        assertTrue(result.content().contains("Not a regular file"));
        assertFalse(result.content().stripLeading().startsWith("{"), result.content());
    }

    @Test
    void executeRunCommandRoutesThroughSubstrateNoShell(@TempDir @NonNull Path tempDir) {
        ToolEngineImpl engine = newEngine();
        // Use the JDK's own java binary (guaranteed present) — argv[] direct exec, no shell.
        String exe =
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        String javaBin = Path.of(System.getProperty("java.home"), "bin", exe).toString();
        ToolCall call =
                new ToolCall(
                        "run_command",
                        Map.of(
                                "commands",
                                List.of(Map.of("executable", javaBin, "args", List.of("-version"))),
                                "connect",
                                "STOP_ON_FAILURE",
                                "timeout",
                                30),
                        "cid-2");
        ToolDefinition definition = definition(engine, "run_command");
        ToolResult result = executeAuthorized(engine, call, definition, tempDir);
        assertTrue(result.success(), "java -version should exit 0");
        assertNotNull(result.content());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void agentRunCommandAndRunTaskUseTheProductionWindowsSandbox(@TempDir @NonNull Path tempDir)
            throws Exception {
        WindowsSandboxFixture fixture = newWindowsSandboxFixture();
        try {
            List<String> probeArgs = List.of("/d", "/c", "echo", "agent-appcontainer-ok");
            Map<String, Object> command = Map.of("executable", "cmd", "args", probeArgs);

            ToolCall runCommand =
                    new ToolCall(
                            "run_command",
                            Map.of(
                                    "commands",
                                    List.of(command),
                                    "connect",
                                    "STOP_ON_FAILURE",
                                    "timeout",
                                    30),
                            "agent-runcmd");
            ToolResult commandResult =
                    executeAuthorized(
                            fixture.engine(),
                            runCommand,
                            definition(fixture.engine(), "run_command"),
                            tempDir);
            assertTrue(commandResult.success(), commandResult.content());
            assertTrue(
                    commandResult.content().contains("agent-appcontainer-ok"),
                    commandResult.content());

            ToolCall runTask =
                    new ToolCall(
                            "run_task",
                            Map.of("commands", List.of(command), "timeout", 30),
                            "agent-runtask");
            ToolResult started =
                    executeAuthorized(
                            fixture.engine(),
                            runTask,
                            definition(fixture.engine(), "run_task"),
                            tempDir);
            assertTrue(started.success(), started.content());
            JsonNode startedJson = fixture.mapper().readTree(started.content());
            assertEquals("started", startedJson.path("status").asText());
            String taskId = startedJson.path("taskId").asText();
            assertFalse(taskId.isBlank());

            JsonNode status = null;
            for (int i = 0; i < 200; i++) {
                ToolCall viewTask =
                        new ToolCall(
                                "view_task",
                                Map.of("taskId", taskId, "lines", 50),
                                "agent-viewtask-" + i);
                ToolResult viewed =
                        executeAuthorized(
                                fixture.engine(),
                                viewTask,
                                definition(fixture.engine(), "view_task"),
                                tempDir);
                assertTrue(viewed.success(), viewed.content());
                status = fixture.mapper().readTree(viewed.content());
                if (!status.path("alive").asBoolean(true)) {
                    break;
                }
                Thread.sleep(50);
            }
            if (status == null) {
                throw new AssertionError("view_task returned no status");
            }
            assertFalse(status.path("alive").asBoolean(true), status.toString());
            assertEquals(0, status.path("exitCode").asInt(-1), status.toString());
            assertTrue(
                    status.path("recentOutput").asText().contains("agent-appcontainer-ok"),
                    status.toString());

            ToolCall stopTask =
                    new ToolCall("stop_task", Map.of("taskId", taskId), "agent-stoptask");
            ToolResult stopped =
                    executeAuthorized(
                            fixture.engine(),
                            stopTask,
                            definition(fixture.engine(), "stop_task"),
                            tempDir);
            assertTrue(stopped.success(), stopped.content());
            assertEquals(
                    "already_exited",
                    fixture.mapper().readTree(stopped.content()).path("status").asText());
        } finally {
            fixture.backgroundTasks().stopAll("test-agent");
            fixture.backgroundTasks().shutdown();
        }
    }

    @Test
    void processExecutionRejectsCommandChangedAfterScreening(@TempDir @NonNull Path tempDir) {
        ToolEngineImpl engine = newEngine();
        String executable =
                Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                System.getProperty("os.name").toLowerCase().contains("win")
                                        ? "java.exe"
                                        : "java")
                        .toString();
        ToolDefinition definition = definition(engine, "run_command");
        ToolCall screened =
                new ToolCall(
                        "run_command",
                        Map.of(
                                "commands",
                                List.of(
                                        Map.of(
                                                "executable",
                                                executable,
                                                "args",
                                                List.of("-version"))),
                                "timeout",
                                30),
                        "cid-screened-command");
        ToolExecutionPermit permit =
                ToolExecutionPermit.capture(
                        screened, definition, Workspace.single(tempDir, PathMode.REAL));
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "test-agent",
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        ToolResultPresentationMode.BASIC,
                        permit));
        try {
            ToolCall changed =
                    new ToolCall(
                            "run_command",
                            Map.of(
                                    "commands",
                                    List.of(
                                            Map.of(
                                                    "executable",
                                                    executable,
                                                    "args",
                                                    List.of("-help"))),
                                    "timeout",
                                    30),
                            "cid-changed-command");
            ToolResult result = engine.execute(changed, definition);
            assertFalse(result.success(), result.content());
            assertTrue(result.content().contains("do not match the screened"), result.content());
        } finally {
            ToolCallContextHolder.clear();
        }
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
                                                        List.of("-version")))),
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
                                    ? "{\"tools\":[{\"name\":\"lookup_event\",\"description\":\"Look up an"
                                            + " event\",\"inputSchema\":{\"type\":\"object\",\"properties\":{\"event_id\":{\"type\":\"string\"}},\"required\":[\"event_id\"]}}]}"
                                    : "{\"content\":[{\"type\":\"text\",\"text\":\"event"
                                            + " found\"}],\"isError\":false}";
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
            ToolCall call = new ToolCall("lookup_event", Map.of("event_id", "event-1"), "remote-1");
            ToolDefinition definition = definition(engine, "lookup_event");
            ToolResult result =
                    executeAuthorized(engine, call, definition, Path.of(".").toAbsolutePath());

            assertTrue(result.success(), result.content());
            assertEquals("event found", result.content());
        } finally {
            server.stop(0);
        }
    }
}
