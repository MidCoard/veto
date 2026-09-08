package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.capability.LoopControlCapabilityImpl;
import top.focess.veto.agent.capability.ProcessExecutionCapabilityImpl;
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

class GuidedExecutionTest {
    private static AgentRunner.@NonNull LlmBinding binding() {
        return new AgentRunner.LlmBinding(
                ProviderType.DEEPSEEK, "scripted", "key", LlmOptions.defaults(), null);
    }

    private static @NonNull AgentService service(
            @NonNull UniformLLMCaller caller, @NonNull HitlRegistry hitl, @NonNull Path root) {
        ObjectMapper mapper = new ObjectMapper();
        var context = mock(ToolDocs.nonNullClass(ApplicationContext.class));
        when(context.getBeansOfType(AgentTool.class))
                .thenReturn(Map.of("think", new ThinkTool(new LoopControlCapabilityImpl())));
        SandboxManager sandbox = new SandboxManager(TestSandboxFactory.uncontainedSubprocesses());
        ToolEngineImpl engine =
                new ToolEngineImpl(
                        mapper,
                        List.of(
                                new ViewFileTool(),
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
        service.setConfiguredDefaultWorkspace(Workspace.single(root, PathMode.REAL));
        for (String id :
                List.of(
                        "read-guided",
                        "command-guided",
                        "bounded-guided",
                        "failed-guided",
                        "tier-guided",
                        "recover-guided")) {
            service.getOrCreateAgent(
                    id,
                    null,
                    binding(),
                    List.of(),
                    UUID.randomUUID(),
                    null,
                    root.toString(),
                    0,
                    ToolResultPresentationMode.BASIC,
                    true);
        }
        return service;
    }

    private static @NonNull VetoResponse message(@NonNull String value) {
        return new VetoResponse(null, null, value, null);
    }

    private static @NonNull VetoResponse actions(@NonNull String json) {
        try {
            return new VetoResponse(
                    null, null, null, new VetoResponse.Guide(new ObjectMapper().readTree(json)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void readGenerateSemanticBranchAndStop(@TempDir @NonNull Path root) throws Exception {
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "Migration guide");
        String path = new ObjectMapper().writeValueAsString(file.toString());
        String program =
                """
            [{"id":"read","label":"Read","type":"tool","tool":"view_file","inputs":{"absolutePath":PATH,"startLine":1},"outputs":{"text":"content"}},
             {"id":"judge","label":"Judge","type":"conditional_goto","check":{"kind":"llm","prompt":"Is migration described?","var":"text"},"true_goto":2,"false_goto":3},
             {"id":"summary","label":"Summary","type":"generate","prompt":"Summarize $document","inputs":{"document":"$text"},"outputs":{"answer":"message"},"temperature":0.2,"thought":false},
             {"id":"finish","label":"Finish","type":"STOP","result_binding":"answer"}]
            """
                        .replace("PATH", path);
        AtomicInteger calls = new AtomicInteger();
        var caller =
                (UniformLLMCaller)
                        request -> {
                            int index = calls.getAndIncrement();
                            if (index == 0) return actions(program);
                            assertTrue(request.userPrompt().contains("Migration guide"));
                            assertTrue(request.tools().isEmpty());
                            if (index == 1) return message("true");
                            assertEquals((Object) 0.2, request.options().temperature());
                            assertFalse(request.userPrompt().contains("$document"));
                            return message("Migration summary");
                        };
        var service = service(caller, new HitlRegistry(), root);
        var result =
                service.submit("read-guided", "Read the notes", binding(), Duration.ofSeconds(15));
        assertTrue(result.success(), result.message());
        assertEquals("Migration summary", result.message());
        assertEquals(3, calls.get());
        var agent = service.agent("read-guided");
        if (agent == null) throw new AssertionError("agent missing");
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

    @Test
    void approvalResumesTypedCommandThenStop(@TempDir @NonNull Path root) throws Exception {
        String executable =
                new ObjectMapper()
                        .writeValueAsString(
                                Path.of(System.getProperty("java.home"), "bin", "java.exe")
                                        .toString());
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
                                            .filter(o -> !o.isRefusal())
                                            .findFirst()
                                            .orElseThrow();
                            assertTrue(
                                    hitl.resolveOption(
                                            prompt.agentId(), prompt.callId(), option.name()));
                        });
        assertTrue(result.success(), result.message());
        assertTrue(result.message().contains("version"), result.message());
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
                                if (index == 2)
                                    return new VetoResponse(
                                            null,
                                            List.of(new ToolCall("think", Map.of())),
                                            null,
                                            null);
                                return message("scoped output");
                            }
                            assertEquals("scripted", request.modelName());
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
                                        null,
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
                                        null,
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
        assertEquals(2, guidedTools.size(), "guide must execute both real sequential file reads");
        assertEquals(2, ordinaryTools.size());
        assertEquals(2, guidedRequests.size(), "direct program plus one generation request");
        assertEquals(
                3,
                ordinaryRequests.size(),
                "two sequential observations plus final model response");
        var enabledSchema = guidedRequests.get(0).responseSchema();
        var disabledSchema = ordinaryRequests.get(0).responseSchema();
        if (enabledSchema == null || disabledSchema == null)
            throw new AssertionError("schema missing");
        assertTrue(enabledSchema.path("properties").has("guide"));
        assertFalse(disabledSchema.path("properties").has("guide"));
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
                                            .get(request.messages().size() - 1)
                                            .content()
                                            .contains("guide"));
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
        assertTrue(toolCalls.isEmpty(), "a disabled guide must never execute even its first tool");
    }
}
