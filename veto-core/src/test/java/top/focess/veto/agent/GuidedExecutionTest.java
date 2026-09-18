package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.capability.LoopControlCapabilityImpl;
import top.focess.veto.agent.capability.ProcessExecutionCapabilityImpl;
import top.focess.veto.agent.capability.ProtectedWorkspaceReadCapabilityImpl;
import top.focess.veto.agent.identity.*;
import top.focess.veto.agent.intercept.*;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.*;
import top.focess.veto.agent.tool.builtin.*;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.agent.workspace.*;
import top.focess.veto.llm.core.*;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.sandbox.*;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.secret.references.SecretCandidateStore;

class GuidedExecutionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void protectedFileReferenceReachesTheModelWithoutRawSecret(
            boolean guided, @TempDir @NonNull Path root) throws Exception {
        String secret = "ghp_" + "A1".repeat(18);
        Path file =
                Files.writeString(root.resolve("config.txt"), "token=" + secret + "\nnext line\n");
        String pathJson = new ObjectMapper().writeValueAsString(file.toString());
        AtomicInteger calls = new AtomicInteger();
        var service =
                service(
                        request -> {
                            assertTrue(
                                    request.messages().stream()
                                            .noneMatch(m -> m.content().contains(secret)));
                            if (calls.getAndIncrement() == 0) {
                                if (!guided)
                                    return new VetoResponse(
                                            null,
                                            List.of(
                                                    new ToolCall(
                                                            "view_file",
                                                            Map.of("absolutePath", file.toString()),
                                                            "read-secret")),
                                            null);
                                return actions(
                                        """
                    [{"id":"read","label":"Read","type":"tool","tool":"view_file","inputs":{"absolutePath":PATH},"outputs":{"text":"content"}},
                     {"id":"answer","label":"Report","type":"generate","prompt":"Report the safe reference in $text","inputs":{"text":"$text"},"outputs":{"answer":"message"}},
                     {"id":"stop","label":"Finish","type":"STOP","result_binding":"answer"}]
                    """
                                                .replace("PATH", pathJson));
                            }
                            assertTrue(
                                    request.messages().stream()
                                            .anyMatch(m -> m.content().contains("[SECRET_REF:s_")));
                            return message("Reference received");
                        },
                        new HitlRegistry(),
                        root);
        String session = UUID.randomUUID().toString();
        var agent =
                service.getOrCreateAgent(
                        session,
                        UUID.randomUUID().toString(),
                        binding(),
                        List.of(),
                        UUID.randomUUID(),
                        "owner",
                        root.toString(),
                        0,
                        ToolResultPresentationMode.BASIC,
                        guided);
        try {
            agent.submit("Read the configuration");
            var result = agent.await(Duration.ofSeconds(10));
            assertTrue(result.success(), result.message());
            assertEquals("Reference received", result.message());
            assertEquals(2, calls.get());
            assertTrue(
                    agent.history().stream()
                            .noneMatch(turn -> turn.payload().toString().contains(secret)));
            assertTrue(
                    agent.history().stream()
                            .anyMatch(
                                    turn ->
                                            turn.type() == TurnType.TOOL_RESPONSE
                                                    && turn.payload()
                                                            .toString()
                                                            .contains("[SECRET_REF:s_")));
        } finally {
            service.remove(session);
        }
    }

    @Test
    void recoveryObservationReachesGuidedGenerationWithoutStartingWork(@TempDir @NonNull Path root)
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var service =
                service(
                        request -> {
                            int index = calls.getAndIncrement();
                            if (index == 0) return message("ready");
                            var observations =
                                    request.messages().stream()
                                            .filter(
                                                    value ->
                                                            value.content()
                                                                    .startsWith(
                                                                            "[Runtime recovery observation]"))
                                            .toList();
                            assertEquals(1, observations.size());
                            assertTrue(
                                    observations
                                            .getFirst()
                                            .content()
                                            .contains("interrupted-attempt"));
                            if (index == 1)
                                return actions(
                                        """
                    [{"id":"answer","label":"Answer","type":"generate","prompt":"Answer the new request","inputs":{},"outputs":{"answer":"message"}},
                     {"id":"stop","label":"Finish","type":"STOP","result_binding":"answer"}]
                    """);
                            return message("new answer");
                        },
                        new HitlRegistry(),
                        root);
        try {
            service.submit("recovered-guided", "Initialize", binding(), Duration.ofSeconds(10));
            var agent = service.agent("recovered-guided");
            if (!(agent instanceof VetoAgent restored)) throw new AssertionError("Agent missing");
            var tasks =
                    List.of(
                            new RecoveredTask(
                                    "group", "old-node", "interrupted-attempt", "old-request"));
            int historySize = restored.history().size();
            restored.setRecoveredTasks(tasks);
            restored.setRecoveredTasks(tasks);
            assertEquals(historySize, restored.history().size());
            assertEquals(1, calls.get());
            var result =
                    service.submit(
                            "recovered-guided", "New request", binding(), Duration.ofSeconds(10));
            assertTrue(result.success(), result.message());
            assertEquals("new answer", result.message());
            assertEquals(3, calls.get());
        } finally {
            service.remove("recovered-guided");
        }
    }

    @Test
    void generatedCitationsFollowTheOutputBindingToStop(@TempDir @NonNull Path root)
            throws Exception {
        var calls = new AtomicInteger();
        var service =
                service(
                        request -> {
                            if (calls.incrementAndGet() == 1)
                                return actions(
                                        """
                [{"id":"answer","label":"Answer","type":"generate","prompt":"Quote the meeting time","response_mode":"CITATIONS","inputs":{},"outputs":{"answer":"message"}},
                 {"id":"stop","label":"Finish","type":"STOP","result_binding":"answer"}]
                """);
                            assertNull(request.responseSchema());
                            assertTrue(
                                    request.tools().stream()
                                            .allMatch(
                                                    t -> t.name().equals("answer_with_citations")));
                            return new VetoResponse(
                                    null,
                                    List.of(
                                            new ToolCall(
                                                    "answer_with_citations",
                                                    Map.of(
                                                            "message",
                                                            "[14:30](cite:meeting)",
                                                            "citations",
                                                            List.of(
                                                                    Map.of(
                                                                            "id",
                                                                            "meeting",
                                                                            "sources",
                                                                            List.of(
                                                                                    Map.of(
                                                                                            "quote",
                                                                                            "14:30"))))))),
                                    null);
                        },
                        new HitlRegistry(),
                        root);
        var result =
                service.submit(
                        "read-guided",
                        "Meeting starts at 14:30",
                        binding(),
                        Duration.ofSeconds(10));
        assertTrue(result.success());
        var agent = service.agent("read-guided");
        if (agent == null) throw new AssertionError("Expected guided agent");
        var answer =
                agent.history().stream()
                        .filter(turn -> turn.type() == TurnType.ASSISTANT_RESPONSE)
                        .toList()
                        .getLast();
        var payload = new ObjectMapper().valueToTree(answer.payload());
        assertEquals(
                "matched",
                payload.path("citation_context").path("checks").get(0).path("status").asText());
        assertEquals("[14:30](cite:meeting)", result.message());
    }

    @Test
    void failedCitationReturnsToolResultAndCanBeCorrected(@TempDir @NonNull Path root)
            throws Exception {
        var calls = new AtomicInteger();
        var service =
                service(
                        request -> {
                            int attempt = calls.getAndIncrement();
                            var definition =
                                    request.tools().stream()
                                            .filter(t -> t.name().equals("answer_with_citations"))
                                            .findFirst()
                                            .orElseThrow();
                            assertFalse(definition.examples().isEmpty());
                            assertTrue(
                                    request.systemPrompt()
                                            .contains("Copy punctuation and whitespace verbatim"));
                            if (attempt == 1)
                                assertTrue(
                                        request.messages().stream()
                                                .anyMatch(
                                                        m ->
                                                                m.content()
                                                                        .contains(
                                                                                "Citation rejected:")));
                            return new VetoResponse(
                                    null,
                                    List.of(
                                            new ToolCall(
                                                    "answer_with_citations",
                                                    Map.of(
                                                            "message",
                                                            "Launch [Friday](cite:launch).",
                                                            "citations",
                                                            List.of(
                                                                    Map.of(
                                                                            "id",
                                                                            "launch",
                                                                            "sources",
                                                                            List.of(
                                                                                    Map.of(
                                                                                            "message_index",
                                                                                            0,
                                                                                            "quote",
                                                                                            attempt
                                                                                                            == 0
                                                                                                    ? "Monday"
                                                                                                    : "Friday"))))))),
                                    null);
                        },
                        new HitlRegistry(),
                        root);
        try {
            var result =
                    service.submit(
                            "citation-retry", "Launch Friday", binding(), Duration.ofSeconds(10));
            assertTrue(result.success(), result.message());
            assertEquals("Launch [Friday](cite:launch).", result.message());
            assertEquals(2, calls.get());
            var agent = service.agent("citation-retry");
            if (agent == null) throw new AssertionError("Agent missing");
            assertEquals(
                    2,
                    agent.history().stream()
                            .filter(t -> t.type() == TurnType.TOOL_RESPONSE)
                            .count());
            assertFalse(
                    agent.history().stream().anyMatch(t -> t.type() == TurnType.EXECUTION_ERROR));
        } finally {
            service.remove("citation-retry");
        }
    }

    @Test
    void responseSubmissionCannotExecuteAlongsideOtherCalls(@TempDir @NonNull Path root)
            throws Exception {
        var calls = new AtomicInteger();
        var service =
                service(
                        request -> {
                            if (calls.getAndIncrement() == 0)
                                return new VetoResponse(
                                        null,
                                        List.of(
                                                new ToolCall(
                                                        "submit_plan",
                                                        Map.of(
                                                                "actions",
                                                                List.of(
                                                                        Map.of(
                                                                                "id", "stop",
                                                                                "label", "Finish",
                                                                                "type", "STOP")))),
                                                new ToolCall("think", Map.of())),
                                        null);
                            assertTrue(
                                    request.messages().stream()
                                            .anyMatch(
                                                    m ->
                                                            m.content()
                                                                    .contains(
                                                                            "No calls in this batch were executed")));
                            return message("Corrected");
                        },
                        new HitlRegistry(),
                        root);
        try {
            var result =
                    service.submit("exclusive-submit", "Hello", binding(), Duration.ofSeconds(10));
            assertTrue(result.success(), result.message());
            assertEquals("Corrected", result.message());
            assertEquals(2, calls.get());
        } finally {
            service.remove("exclusive-submit");
        }
    }

    @Test
    void boundInputCannotHideAMalformedLaterStepBeforeTheFirstToolRuns(@TempDir @NonNull Path root)
            throws Exception {
        Path notes = root.resolve("notes.txt");
        Files.writeString(notes, "A source document");
        String program =
                """
                [{"id":"read","label":"Read source","type":"tool","tool":"view_file",
                  "inputs":{"absolutePath":PATH},"outputs":{"document":"content"}},
                 {"id":"bad","label":"Invalid later read","type":"tool","tool":"view_file",
                  "inputs":{"absolutePath":"$document","unknownArgument":true},"outputs":{}},
                 {"id":"stop","label":"Finish","type":"STOP","result_binding":"document"}]
                """
                        .replace("PATH", new ObjectMapper().writeValueAsString(notes.toString()));
        AtomicInteger calls = new AtomicInteger();
        var service =
                service(
                        request ->
                                calls.getAndIncrement() == 0
                                        ? actions(program)
                                        : message("Plan rejected; no file read."),
                        new HitlRegistry(),
                        root);
        try {
            List<AgentRunner.ToolCallEvent> events = new ArrayList<>();
            var result =
                    service.submit(
                            "invalid-bound-plan",
                            "Hello",
                            binding(),
                            Duration.ofSeconds(10),
                            null,
                            null,
                            null,
                            events::add,
                            null);
            assertTrue(result.success(), result.message());
            assertTrue(
                    events.stream().allMatch(event -> event.toolName().equals("submit_plan")),
                    "The complete plan must pass before even its first valid file step can execute");
            var agent = service.agent("invalid-bound-plan");
            if (agent == null) throw new AssertionError("agent missing");
            assertTrue(
                    agent.history().stream()
                            .anyMatch(
                                    turn ->
                                            turn.type() == TurnType.TOOL_RESPONSE
                                                    && Boolean.FALSE.equals(
                                                            turn.payload().get("success"))
                                                    && String.valueOf(turn.payload().get("content"))
                                                            .contains("unknownArgument")));
        } finally {
            service.remove("invalid-bound-plan");
        }
    }

    @Test
    void invalidProgramIsRejectedAsToolFailureAndSessionContinues(@TempDir @NonNull Path root)
            throws Exception {
        var calls = new AtomicInteger();
        var service =
                service(
                        request -> {
                            int attempt = calls.getAndIncrement();
                            if (attempt == 0)
                                return actions(
                                        "[{\"id\":\"jump\",\"label\":\"Jump\",\"type\":\"goto\",\"index\":99},{\"id\":\"end\",\"label\":\"Finish\",\"type\":\"STOP\"}]");
                            if (attempt == 1) {
                                assertTrue(
                                        request.messages().stream()
                                                .anyMatch(m -> m.role().equals("tool")));
                                return actions(
                                        "[{\"id\":\"g\",\"label\":\"Answer\",\"type\":\"generate\",\"prompt\":\"Say hello\",\"outputs\":{\"answer\":\"message\"}},{\"id\":\"end\",\"label\":\"Finish\",\"type\":\"STOP\",\"result_binding\":\"answer\"}]");
                            }
                            return message("Hello");
                        },
                        new HitlRegistry(),
                        root);
        try {
            var result =
                    service.submit("program-retry", "Hello", binding(), Duration.ofSeconds(10));
            assertTrue(result.success(), result.message());
            assertEquals("Hello", result.message());
            assertEquals(3, calls.get());
        } finally {
            service.remove("program-retry");
        }
    }

    private static AgentRunner.@NonNull LlmBinding binding() {
        return new AgentRunner.LlmBinding(
                ProviderType.DEEPSEEK, "scripted", "key", LlmOptions.defaults(), null);
    }

    private static @NonNull AgentService service(
            @NonNull UniformLLMCaller caller, @NonNull HitlRegistry hitl, @NonNull Path root) {
        ObjectMapper mapper = new ObjectMapper();
        var candidates = new SecretCandidateStore();
        var context = mock(ToolDocs.nonNullClass(ApplicationContext.class));
        when(context.getBeansOfType(AgentTool.class))
                .thenReturn(
                        Map.of(
                                "think",
                                new ThinkTool(new LoopControlCapabilityImpl()),
                                "submit_plan",
                                new SubmitPlanTool(new LoopControlCapabilityImpl()),
                                "answer_with_citations",
                                new AnswerWithCitationsTool(new LoopControlCapabilityImpl())));
        SandboxManager sandbox = new SandboxManager(TestSandboxFactory.uncontainedSubprocesses());
        ToolEngineImpl engine =
                new ToolEngineImpl(
                        mapper,
                        List.of(
                                new ViewFileTool(
                                        new ProtectedWorkspaceReadCapabilityImpl(candidates)),
                                new RunCommandTool(
                                        new ProcessExecutionCapabilityImpl(
                                                sandbox, new BackgroundTaskManager(sandbox)))),
                        context);
        ReflectionTestUtils.invokeMethod(engine, "init");
        PromptCompiler compiler =
                new PromptCompiler(
                        new DefaultCapabilityTranslator(mapper),
                        new SystemPromptResolver(),
                        mapper,
                        "FULL_ACCESS");
        ReflectionTestUtils.setField(compiler, "maxInputTokens", 32000);
        ReflectionTestUtils.setField(compiler, "contextFillRatio", 0.9);
        AgentService service =
                new AgentService(
                        engine,
                        hitl,
                        new IngressDefense(),
                        compiler,
                        caller,
                        mapper,
                        List.of(),
                        new RoleToolFilter(engine),
                        "REAL",
                        50,
                        1000,
                        "FULL_ACCESS",
                        "STRICT",
                        null,
                        null,
                        new BackgroundTaskManager(sandbox));
        service.attachSecretCandidates(candidates);
        service.setConfiguredDefaultWorkspace(Workspace.single(root, PathMode.REAL));
        for (String id :
                List.of(
                        "read-guided",
                        "citation-retry",
                        "exclusive-submit",
                        "invalid-bound-plan",
                        "program-retry",
                        "command-guided",
                        "bounded-guided",
                        "failed-guided",
                        "tier-guided",
                        "recover-guided",
                        "recovered-guided")) {
            service.getOrCreateAgent(
                    id,
                    null,
                    binding(),
                    List.of(),
                    UUID.randomUUID(),
                    "owner",
                    root.toString(),
                    0,
                    ToolResultPresentationMode.BASIC,
                    true);
        }
        for (String id : List.of("ordinary-comparison", "disabled-guide")) {
            service.getOrCreateAgent(
                    id,
                    null,
                    binding(),
                    List.of(),
                    UUID.randomUUID(),
                    "owner",
                    root.toString(),
                    0,
                    ToolResultPresentationMode.BASIC,
                    false);
        }
        return service;
    }

    private static @NonNull VetoResponse message(@NonNull String value) {
        return new VetoResponse(null, null, value);
    }

    private static @NonNull VetoResponse actions(@NonNull String json) {
        try {
            return new VetoResponse(
                    null,
                    List.of(
                            new ToolCall(
                                    "submit_plan",
                                    Map.of(
                                            "actions",
                                            new ObjectMapper().readValue(json, List.class)))),
                    null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void readGenerateSemanticBranchRepairsInvalidPredicateAndStop(@TempDir @NonNull Path root)
            throws Exception {
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "Migration guide");
        String path = new ObjectMapper().writeValueAsString(file.toString());
        String program =
                """
            [{"id":"read","label":"Read","type":"tool","tool":"view_file","inputs":{"absolutePath":PATH,"startLine":1},"outputs":{"text":"content"}},
             {"id":"judge","label":"Judge","type":"conditional_goto","check":{"kind":"llm","prompt":"Is migration described?","var":"text"},"true_goto":2,"false_goto":4},
             {"id":"summary","label":"Summary","type":"generate","prompt":"Summarize $document","inputs":{"document":"$text"},"outputs":{"answer":"message"},"temperature":0.2},
             {"id":"finish","label":"Finish","type":"STOP","result_binding":"answer"},
             {"id":"empty","label":"No match","type":"STOP"}]
            """
                        .replace("PATH", path);
        AtomicInteger calls = new AtomicInteger();
        var caller =
                (UniformLLMCaller)
                        request -> {
                            int index = calls.getAndIncrement();
                            if (index == 0) return actions(program);
                            assertTrue(request.userPrompt().contains("Migration guide"));
                            if (index == 1 || index == 2) {
                                assertEquals(
                                        ResponseContract.Mode.PREDICATE,
                                        request.responseContract().mode());
                                assertTrue(request.tools().isEmpty());
                                assertFalse(request.nativeToolsEnabled());
                                assertFalse(request.systemPrompt().contains("### `submit_plan`"));
                                if (index == 1)
                                    return message("Yes, the guide describes migration.");
                                String correction = request.messages().getLast().content();
                                assertTrue(
                                        correction.contains("exactly true or false as plain text"));
                                assertFalse(correction.contains("answer_with_citations"));
                                return message("true");
                            }
                            assertEquals(
                                    ResponseContract.Mode.GENERATION,
                                    request.responseContract().mode());
                            assertTrue(request.tools().isEmpty());
                            assertFalse(request.nativeToolsEnabled());
                            assertEquals((Object) 0.2, request.options().temperature());
                            assertFalse(request.userPrompt().contains("$document"));
                            return message("Migration summary");
                        };
        var service = service(caller, new HitlRegistry(), root);
        var result =
                service.submit("read-guided", "Read the notes", binding(), Duration.ofSeconds(15));
        assertTrue(result.success(), result.message());
        assertEquals("Migration summary", result.message());
        assertEquals(4, calls.get());
        var agent = service.agent("read-guided");
        if (agent == null) throw new AssertionError("agent missing");
        assertTrue(
                agent.history().stream()
                        .noneMatch(turn -> turn.type() == TurnType.ASSISTANT_THOUGHT),
                "A submitted plan must not be serialized as model reasoning");
        var submitted =
                agent.history().stream()
                        .filter(
                                turn ->
                                        turn.type() == TurnType.TOOL_CALL
                                                && "submit_plan"
                                                        .equals(turn.payload().get("tool_name")))
                        .toList();
        assertEquals(1, submitted.size(), "The native call is the single canonical plan record");
        var planArgs = new ObjectMapper().valueToTree(submitted.getFirst().payload()).path("args");
        assertTrue(planArgs.has("actions"));
        assertFalse(planArgs.has("thought"));
        assertFalse(planArgs.has("message"));
        assertEquals(
                List.of("Migration summary"),
                agent.history().stream()
                        .filter(turn -> turn.type() == TurnType.ASSISTANT_RESPONSE)
                        .map(turn -> turn.payload().get("content"))
                        .toList(),
                "Intermediate generation and semantic judgments must not become user answers");
        assertFalse(
                agent.history().stream()
                        .anyMatch(
                                turn ->
                                        turn.type() == TurnType.TOOL_RESPONSE
                                                && Boolean.FALSE.equals(
                                                        turn.payload().get("success"))),
                "Normal STOP must not be recorded as a failed tool");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void approvalResumesTypedCommandThenStop(boolean approve, @TempDir @NonNull Path root)
            throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        if (!Files.isExecutable(java)) java = java.resolveSibling("java.exe");
        String executable = new ObjectMapper().writeValueAsString(java.toString());
        String program =
                """
            [{"id":"command","label":"Java version","type":"tool","tool":"run_command","inputs":{"commands":[{"executable":JAVA,"args":["-version"]}],"timeout":10,"network":false},"outputs":{"output":"content"}},
             {"id":"finish","label":"Finish","type":"STOP","result_binding":"output"}]
            """
                        .replace("JAVA", executable);
        AtomicInteger calls = new AtomicInteger();
        HitlRegistry hitl = new HitlRegistry();
        AtomicInteger approvals = new AtomicInteger();
        var service =
                service(
                        request -> {
                            calls.incrementAndGet();
                            return actions(program);
                        },
                        hitl,
                        root);
        var result =
                service.submit(
                        "command-guided",
                        "Report the installed Java version",
                        binding(),
                        Duration.ofSeconds(20),
                        null,
                        prompt -> {
                            approvals.incrementAndGet();
                            VetoOption option =
                                    prompt.options().stream()
                                            .filter(
                                                    o ->
                                                            approve
                                                                    ? !o.isRefusal()
                                                                    : o.isRefusal()
                                                                            && !o
                                                                                    .isDeclineAndContinue())
                                            .findFirst()
                                            .orElseThrow();
                            assertTrue(
                                    hitl.resolveOption(
                                            prompt.agentId(), prompt.callId(), option.name()));
                        });
        assertEquals(approve, result.success(), result.message());
        if (approve) assertTrue(result.message().contains("version"), result.message());
        else {
            assertTrue(result.message().contains("Approval was requested"), result.message());
            assertTrue(result.message().contains("The tool was not executed"), result.message());
            assertEquals(1, calls.get(), "Rejected GUIDE must not regenerate or retry");
        }
        assertTrue(approvals.get() > 0, "exercise the actual approval/resume path");
    }

    @Test
    void conditionalLoopStopsAtRuntimeBudget(@TempDir @NonNull Path root) throws Exception {
        String program =
                """
            [{"id":"spin","label":"Spin","type":"conditional_goto","check":{"kind":"empty","var":"unset"},"true_goto":0,"false_goto":1},
             {"id":"finish","label":"Finish","type":"STOP"}]
            """;
        AtomicInteger calls = new AtomicInteger();
        var service =
                service(
                        request -> {
                            calls.incrementAndGet();
                            return actions(program);
                        },
                        new HitlRegistry(),
                        root);
        var bounded = service.agent("bounded-guided");
        if (bounded == null) throw new AssertionError("agent missing");
        Object boundedRunner = ReflectionTestUtils.getField(bounded, "runner");
        if (boundedRunner == null) throw new AssertionError("runner missing");
        ReflectionTestUtils.setField(boundedRunner, "maxGuidedSteps", 5);
        var result =
                service.submit(
                        "bounded-guided",
                        "Exercise bounded loop",
                        binding(),
                        Duration.ofSeconds(10));
        assertFalse(result.success());
        assertEquals(1, calls.get());
    }

    @Test
    void unhandledToolFailureCannotReportSuccess(@TempDir @NonNull Path root) throws Exception {
        String path = new ObjectMapper().writeValueAsString(root.resolve("absent.txt").toString());
        String program =
                """
            [{"id":"read","label":"Read","type":"tool","tool":"view_file","inputs":{"absolutePath":PATH},"outputs":{"output":"content"}},
             {"id":"finish","label":"Finish","type":"STOP","result_binding":"output"}]
            """
                        .replace("PATH", path);
        AtomicInteger calls = new AtomicInteger();
        var result =
                service(
                                request -> {
                                    calls.incrementAndGet();
                                    return actions(program);
                                },
                                new HitlRegistry(),
                                root)
                        .submit(
                                "failed-guided",
                                "Read missing file",
                                binding(),
                                Duration.ofSeconds(10));
        assertFalse(result.success());
    }

    @Test
    void generationBudgetsTheScopedManifestBeforeDispatch(@TempDir @NonNull Path root)
            throws Exception {
        AtomicReference<AgentService> serviceRef = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger budget = new AtomicInteger();
        var service =
                service(
                        request -> {
                            var active = serviceRef.get();
                            if (active == null) throw new AssertionError("Missing service");
                            var agent = active.agent("read-guided");
                            if (agent == null) throw new AssertionError("Missing agent");
                            if (!(ReflectionTestUtils.getField(active, "promptCompiler")
                                    instanceof PromptCompiler compiler))
                                throw new AssertionError("Missing compiler");
                            if (calls.getAndIncrement() == 0) {
                                var projection =
                                        new VetoRequest(
                                                request.systemPrompt(),
                                                request.userPrompt(),
                                                List.of(),
                                                request.providerType(),
                                                request.modelName(),
                                                request.credentialKey(),
                                                request.options(),
                                                request.messages(),
                                                request.responseSchema(),
                                                request.baseUrl(),
                                                false,
                                                ResponseContract.generation());
                                projection =
                                        compiler.scopeRequest(
                                                projection,
                                                agent.persona(),
                                                Workspace.single(root, PathMode.REAL),
                                                null,
                                                ToolResultPresentationMode.BASIC);
                                long fullSize = compiler.estimateRequest(request, 1);
                                long scopedSize = compiler.estimateRequest(projection, 1);
                                assertTrue(
                                        fullSize - scopedSize > 1000,
                                        "The full plan/tool catalog materially exceeds the generation catalog");
                                budget.set(Math.toIntExact(fullSize - 1));
                                ReflectionTestUtils.setField(
                                        compiler, "maxInputTokens", budget.get());
                                ReflectionTestUtils.setField(compiler, "contextFillRatio", 1.0);
                                return actions(
                                        """
                        [{"id":"generate","label":"Compose","type":"generate","prompt":"Say hello","outputs":{"answer":"message"}},
                         {"id":"finish","label":"Finish","type":"STOP","result_binding":"answer"}]
                        """);
                            }
                            assertEquals(
                                    ResponseContract.Mode.GENERATION,
                                    request.responseContract().mode());
                            assertTrue(compiler.estimateRequest(request, 1) <= budget.get());
                            return message("Hello");
                        },
                        new HitlRegistry(),
                        root);
        serviceRef.set(service);
        var result =
                service.submit(
                        "read-guided", "Say hello using a plan", binding(), Duration.ofSeconds(10));
        assertTrue(result.success(), result.message());
        assertEquals("Hello", result.message());
        assertEquals(2, calls.get());
    }

    @Test
    void generationOverridesAreScopedAndToolCallsAreRejected(@TempDir @NonNull Path root)
            throws Exception {
        String program =
                """
            [{"id":"generate","label":"Generate","type":"generate","prompt":"Summarize $input","inputs":{"input":"evidence"},"outputs":{"answer":"message"},"model_tier":"LOW","temperature":0.25},
             {"id":"finish","label":"Finish","type":"STOP","result_binding":"answer"}]
            """;
        AtomicInteger calls = new AtomicInteger();
        var service =
                service(
                        request -> {
                            int index = calls.getAndIncrement();
                            if (index == 0) return message("ready");
                            if (index == 1) return actions(program);
                            if (index == 2 || index == 3) {
                                assertEquals("tier-model", request.modelName());
                                assertEquals("tier-key", request.credentialKey());
                                assertEquals("https://example.invalid", request.baseUrl());
                                assertEquals((Object) 0.25, request.options().temperature());
                                assertEquals((Object) 1234, request.options().maxTokens());
                                assertEquals(
                                        ResponseContract.Mode.GENERATION,
                                        request.responseContract().mode());
                                assertTrue(request.tools().isEmpty());
                                assertFalse(request.nativeToolsEnabled());
                                assertFalse(request.systemPrompt().contains("### `think`"));
                                if (index == 2)
                                    return new VetoResponse(
                                            null, List.of(new ToolCall("think", Map.of())), null);
                                return message("scoped output");
                            }
                            assertEquals("scripted", request.modelName());
                            assertEquals(
                                    ResponseContract.Mode.ORDINARY,
                                    request.responseContract().mode());
                            return message("original binding retained");
                        },
                        new HitlRegistry(),
                        root);
        var tiers = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        when(tiers.resolve("owner", ModelTier.LOW))
                .thenReturn(
                        new ModelBinding(
                                ProviderType.DEEPSEEK,
                                "tier-model",
                                "tier-key",
                                0.7,
                                1234,
                                "https://example.invalid"));
        service.setGuidedTierRegistry(tiers);
        service.submit("tier-guided", "Initialize", binding(), Duration.ofSeconds(10));
        var agent = service.agent("tier-guided");
        if (agent == null) throw new AssertionError("agent missing");
        Object value = ReflectionTestUtils.getField(agent, "runner");
        if (!(value instanceof AgentRunner runner)) throw new AssertionError("runner missing");
        runner.setOwner("owner");
        runner.configureGuided(tiers, 1000);
        var result = service.submit("tier-guided", "Summarize", binding(), Duration.ofSeconds(10));
        assertTrue(result.success(), result.message());
        assertEquals("scoped output", result.message());
        assertTrue(
                service.submit(
                                "tier-guided",
                                "Continue normally",
                                binding(),
                                Duration.ofSeconds(10))
                        .success());
        assertEquals(5, calls.get());
    }

    @Test
    void explicitFailureBranchRecovers(@TempDir @NonNull Path root) throws Exception {
        String path = new ObjectMapper().writeValueAsString(root.resolve("missing.txt").toString());
        String program =
                """
            [{"id":"read","label":"Read","type":"tool","tool":"view_file","inputs":{"absolutePath":PATH},"outputs":{"error":"content"}},
             {"id":"check","label":"Check","type":"conditional_goto","check":{"kind":"exit_ok","step_id":"read"},"true_goto":3,"false_goto":2},
             {"id":"recover","label":"Recover","type":"generate","prompt":"Explain why the file could not be read: $error","inputs":{},"outputs":{"answer":"message"}},
             {"id":"finish","label":"Finish","type":"STOP","result_binding":"answer"}]
            """
                        .replace("PATH", path);
        AtomicInteger calls = new AtomicInteger();
        var result =
                service(
                                request -> {
                                    int index = calls.getAndIncrement();
                                    if (index == 0) return actions(program);
                                    assertFalse(request.userPrompt().contains("$error"));
                                    return message(
                                            "The file is missing; provide an existing path.");
                                },
                                new HitlRegistry(),
                                root)
                        .submit(
                                "recover-guided",
                                "Read missing file",
                                binding(),
                                Duration.ofSeconds(10));
        assertTrue(result.success(), result.message());
        assertEquals("The file is missing; provide an existing path.", result.message());
    }

    @Test
    void sameSequentialFileTaskUsesTwoGuidedCallsOrThreeOrdinaryCalls(@TempDir @NonNull Path root)
            throws Exception {
        Path index = root.resolve("release.txt");
        Path file = root.resolve("version.txt");
        Files.writeString(index, "release=stable");
        Files.writeString(file, "version=42");
        String indexPath = new ObjectMapper().writeValueAsString(index.toString());
        String versionPath = new ObjectMapper().writeValueAsString(file.toString());
        String program =
                """
                [{"id":"index","label":"Read release","type":"tool","tool":"view_file","inputs":{"absolutePath":PATH},"outputs":{"release":"content"}},
                 {"id":"read","label":"Read version","type":"tool","tool":"view_file","inputs":{"absolutePath":VERSION_PATH},"outputs":{"version":"content"}},
                 {"id":"summary","label":"Summarize version","type":"generate","prompt":"Report release $releaseName and version $text","inputs":{"text":"$version","releaseName":"$release"},"outputs":{"answer":"message"}},
                 {"id":"finish","label":"Return version","type":"STOP","result_binding":"answer"}]
                """
                        .replace("VERSION_PATH", versionPath)
                        .replace("PATH", indexPath);
        List<VetoRequest> guidedRequests = new ArrayList<>();
        var guided =
                service(
                        request -> {
                            guidedRequests.add(request);
                            if (guidedRequests.size() == 1) return actions(program);
                            assertTrue(request.userPrompt().contains("release=stable"));
                            assertTrue(request.userPrompt().contains("version=42"));
                            return message("Stable release, version 42.");
                        },
                        new HitlRegistry(),
                        root);
        List<VetoRequest> ordinaryRequests = new ArrayList<>();
        var ordinary =
                service(
                        request -> {
                            ordinaryRequests.add(request);
                            if (ordinaryRequests.size() == 1)
                                return new VetoResponse(
                                        null,
                                        List.of(
                                                new ToolCall(
                                                        "view_file",
                                                        Map.of("absolutePath", index.toString()))),
                                        null);
                            if (ordinaryRequests.size() == 2) {
                                assertTrue(
                                        request.messages().stream()
                                                .anyMatch(
                                                        m ->
                                                                m.content()
                                                                        .contains(
                                                                                "release=stable")));
                                return new VetoResponse(
                                        null,
                                        List.of(
                                                new ToolCall(
                                                        "view_file",
                                                        Map.of("absolutePath", file.toString()))),
                                        null);
                            }
                            assertTrue(
                                    request.messages().stream()
                                            .anyMatch(m -> m.content().contains("version=42")));
                            return message("Stable release, version 42.");
                        },
                        new HitlRegistry(),
                        root);
        String task = "Read release.txt, then version.txt, and report the release and version.";
        List<AgentRunner.ToolCallEvent> guidedTools = new ArrayList<>();
        List<AgentRunner.ToolCallEvent> ordinaryTools = new ArrayList<>();
        var guidedResult =
                guided.submit(
                        "read-guided",
                        task,
                        binding(),
                        Duration.ofSeconds(10),
                        null,
                        null,
                        null,
                        guidedTools::add,
                        null);
        var ordinaryResult =
                ordinary.submit(
                        "ordinary-comparison",
                        task,
                        binding(),
                        Duration.ofSeconds(10),
                        null,
                        null,
                        null,
                        ordinaryTools::add,
                        null);
        assertTrue(guidedResult.success(), guidedResult.message());
        assertTrue(ordinaryResult.success(), ordinaryResult.message());
        assertEquals("Stable release, version 42.", guidedResult.message());
        assertEquals(guidedResult.message(), ordinaryResult.message());
        assertEquals(
                2,
                guidedTools.stream().filter(t -> !t.toolName().equals("submit_plan")).count(),
                "guide must execute both real sequential file reads");
        assertEquals(2, ordinaryTools.size());
        assertEquals(2, guidedRequests.size(), "direct program plus one generation request");
        assertEquals(
                3,
                ordinaryRequests.size(),
                "two sequential observations plus final model response");
        assertTrue(
                guidedRequests.get(1).tools().stream()
                        .allMatch(t -> t.name().equals("answer_with_citations")));
        assertNotEquals(guidedRequests.get(0).systemPrompt(), guidedRequests.get(1).systemPrompt());
        assertEquals(
                ResponseContract.Mode.GENERATION, guidedRequests.get(1).responseContract().mode());
        assertFalse(guidedRequests.get(1).systemPrompt().contains("### `submit_plan`"));
        assertFalse(guidedRequests.get(1).systemPrompt().contains("### `view_file`"));
        assertEquals(
                guidedRequests.get(0).responseSchema(), guidedRequests.get(1).responseSchema());
        assertNull(guidedRequests.get(0).responseSchema());
        assertNull(ordinaryRequests.get(0).responseSchema());
        assertTrue(
                guidedRequests.get(0).tools().stream()
                        .anyMatch(t -> t.name().equals("submit_plan")));
        assertFalse(
                ordinaryRequests.get(0).tools().stream()
                        .anyMatch(t -> t.name().equals("submit_plan")));
        String enabledPrompt = guidedRequests.get(0).systemPrompt();
        String disabledPrompt = ordinaryRequests.get(0).systemPrompt();
        System.out.println(
                "Guided comparison: enabled prompt chars="
                        + enabledPrompt.length()
                        + ", disabled prompt chars="
                        + disabledPrompt.length()
                        + ", model requests="
                        + guidedRequests.size()
                        + "/"
                        + ordinaryRequests.size()
                        + ", tool calls="
                        + guidedTools.size()
                        + "/"
                        + ordinaryTools.size()
                        + ", final answer="
                        + guidedResult.message());
        assertNotEquals(enabledPrompt, disabledPrompt);
        assertTrue(enabledPrompt.contains("guide"));
        assertFalse(disabledPrompt.contains("conditional_goto"));
    }

    @Test
    void disabledSessionRejectsGuideBeforeExecutingItsTool(@TempDir @NonNull Path root)
            throws Exception {
        String path =
                new ObjectMapper().writeValueAsString(root.resolve("must-not-read.txt").toString());
        String program =
                """
                [{"id":"read","label":"Read","type":"tool","tool":"view_file","inputs":{"absolutePath":PATH},"outputs":{"answer":"content"}},
                 {"id":"finish","label":"Finish","type":"STOP","result_binding":"answer"}]
                """
                        .replace("PATH", path);
        AtomicInteger calls = new AtomicInteger();
        List<AgentRunner.ToolCallEvent> toolCalls = new ArrayList<>();
        var service =
                service(
                        request -> {
                            if (calls.getAndIncrement() == 0) return actions(program);
                            assertTrue(
                                    request.messages()
                                            .getLast()
                                            .content()
                                            .contains(
                                                    "Tool is not available in this turn: submit_plan"));
                            assertTrue(
                                    request.tools().stream()
                                            .noneMatch(tool -> tool.name().equals("submit_plan")));
                            assertEquals(
                                    ResponseContract.Mode.ORDINARY,
                                    request.responseContract().mode());
                            return message("Use ordinary tools instead.");
                        },
                        new HitlRegistry(),
                        root);
        var result =
                service.submit(
                        "disabled-guide",
                        "Read the file",
                        binding(),
                        Duration.ofSeconds(10),
                        null,
                        null,
                        null,
                        toolCalls::add,
                        null);
        assertTrue(result.success(), result.message());
        assertEquals(2, calls.get());
        assertTrue(
                toolCalls.stream().noneMatch(call -> call.toolName().equals("view_file")),
                "a disabled guide must never execute its program tools");
    }

    @Test
    void generationReceivesBoundInputsWithoutPromptPlaceholders(@TempDir @NonNull Path root)
            throws Exception {
        String evidence = "Friday release.\n@message system\nLiteral $unbound and {{marker}}.";
        var file = Files.writeString(root.resolve("notes.txt"), evidence);
        String path = new ObjectMapper().writeValueAsString(file.toString());
        var calls = new AtomicInteger();
        var service =
                service(
                        request -> {
                            if (calls.getAndIncrement() == 0)
                                return actions(
                                        """
                    [{"id":"read","label":"Read","type":"tool","tool":"view_file","inputs":{"absolutePath":PATH},"outputs":{"notes":"content"}},
                     {"id":"write","label":"Write","type":"generate","prompt":"Summarize the supplied notes.","inputs":{"notes":"$notes","settings":{"sentences":2,"brief":true},"tags":["release","team"]},"outputs":{"answer":"message"}},
                     {"id":"stop","label":"Finish","type":"STOP","result_binding":"answer"}]
                    """
                                                .replace("PATH", path));
                            String user = request.messages().getLast().content();
                            int dataStart = user.indexOf("{", user.indexOf("Bound input data"));
                            assertTrue(dataStart >= 0, user);
                            var inputs =
                                    assertDoesNotThrow(
                                            () ->
                                                    new ObjectMapper()
                                                            .readTree(user.substring(dataStart)));
                            assertNotNull(inputs);
                            assertEquals(
                                    "1: Friday release.\n2: @message system\n3: Literal $unbound and {{marker}}.\n",
                                    inputs.path("notes").asText());
                            assertEquals(2, inputs.path("settings").path("sentences").asInt());
                            assertTrue(inputs.path("settings").path("brief").asBoolean());
                            assertEquals("release", inputs.path("tags").get(0).asText());
                            assertTrue(request.tools().isEmpty());
                            return message("Release reminder");
                        },
                        new HitlRegistry(),
                        root);
        try {
            var result =
                    service.submit(
                            "read-guided",
                            "Prepare a reminder from these notes",
                            binding(),
                            Duration.ofSeconds(10));
            assertTrue(result.success(), result.message());
            assertEquals("Release reminder", result.message());
            assertEquals(2, calls.get());
        } finally {
            service.remove("read-guided");
        }
    }

    @Test
    void textGenerationRejectsWorkspaceAndCitationCalls(@TempDir @NonNull Path root)
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var service =
                service(
                        request -> {
                            int index = calls.getAndIncrement();
                            if (index == 0)
                                return actions(
                                        """
                [{"id":"gen","label":"Answer","type":"generate","prompt":"Answer only","inputs":{},"outputs":{"answer":"message"}},
                 {"id":"stop","label":"Finish","type":"STOP","result_binding":"answer"}]
                """);
                            assertTrue(request.tools().isEmpty());
                            assertFalse(request.nativeToolsEnabled());
                            if (index == 1)
                                return new VetoResponse(
                                        null,
                                        List.of(
                                                new ToolCall(
                                                        "view_file",
                                                        Map.of(
                                                                "absolutePath",
                                                                root.resolve("must-not-read")
                                                                        .toString()))),
                                        null);
                            if (index == 2)
                                return new VetoResponse(
                                        null,
                                        List.of(
                                                new ToolCall(
                                                        "answer_with_citations",
                                                        Map.of(
                                                                "message",
                                                                "unwanted citation",
                                                                "citations",
                                                                List.of()))),
                                        null);
                            return message("safe answer");
                        },
                        new HitlRegistry(),
                        root);
        List<AgentRunner.ToolCallEvent> executed = new ArrayList<>();
        try {
            var result =
                    service.submit(
                            "read-guided",
                            "Answer without tools",
                            binding(),
                            Duration.ofSeconds(10),
                            null,
                            null,
                            null,
                            executed::add,
                            null);
            assertTrue(result.success(), result.message());
            assertEquals("safe answer", result.message());
            assertEquals(4, calls.get());
            assertTrue(
                    executed.stream().allMatch(t -> t.toolName().equals("submit_plan")),
                    executed.toString());
        } finally {
            service.remove("read-guided");
        }
    }

    @Test
    void generatedPathProvenanceReachesActualGateway(@TempDir @NonNull Path root) throws Exception {
        Path file = Files.writeString(root.resolve("source.txt"), "source data");
        AtomicInteger calls = new AtomicInteger();
        var service =
                service(
                        request -> {
                            if (calls.getAndIncrement() == 0)
                                return actions(
                                        """
                [{"id":"choose","label":"Choose path","type":"generate","prompt":"Return path","inputs":{},"outputs":{"path":"message"}},
                 {"id":"read","label":"Read chosen file","type":"tool","tool":"view_file","inputs":{"absolutePath":"$path"},"outputs":{"text":"content"}},
                 {"id":"stop","label":"Finish","type":"STOP","result_binding":"text"}]
                """);
                            return message(file.toString());
                        },
                        new HitlRegistry(),
                        root);
        var agent = service.agent("read-guided");
        if (agent == null) throw new AssertionError("Missing agent");
        if (!(ReflectionTestUtils.getField(agent, "runner") instanceof AgentRunner runner))
            throw new AssertionError("Missing runner");
        if (!(ReflectionTestUtils.getField(runner, "gateway") instanceof Gateway original))
            throw new AssertionError("Missing gateway");
        Gateway observed = spy(original);
        ReflectionTestUtils.setField(runner, "gateway", observed);
        try {
            var result =
                    service.submit(
                            "read-guided",
                            "Read the selected file",
                            binding(),
                            Duration.ofSeconds(10));
            assertTrue(result.success(), result.message());
            var capture = ArgumentCaptor.forClass(ToolDocs.nonNullClass(GuidedStepContext.class));
            verify(observed)
                    .screen(
                            any(),
                            any(),
                            eq("Read the selected file"),
                            isNull(),
                            isNull(),
                            capture.capture());
            assertEquals("read", capture.getValue().stepId());
            String source = capture.getValue().inputSources().get("absolutePath");
            assertTrue(source != null && source.startsWith("choose:"));
        } finally {
            service.remove("read-guided");
        }
    }
}
