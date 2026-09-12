package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.capability.NetworkEgressCapabilityImpl;
import top.focess.veto.agent.capability.WebReadCapability;
import top.focess.veto.agent.identity.RoleToolFilter;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.intercept.VetoOption;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.AgentTool;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolEngineImpl;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.agent.web.FetchedPage;
import top.focess.veto.agent.web.WebFetchExecutor;
import top.focess.veto.agent.web.WebFetchTool;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.memory.TurnRecordEntity;
import top.focess.veto.memory.TurnRecordRepository;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.sandbox.BackgroundTaskManager;

class WebReadAgentIntegrationTest {
    @ParameterizedTest
    @CsvSource({
        "BASIC,false,5",
        "DETAILED,false,5",
        "BASIC,true,5",
        "DETAILED,true,5",
        "BASIC,false,3",
        "DETAILED,true,3",
        "BASIC,false,4",
        "DETAILED,true,4"
    })
    void parentContextAndReplayedHistoryContainOnlyTerminalEvidence(
            @NonNull ToolResultPresentationMode presentation, boolean guided, int maxRounds)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        SessionAgentRegistry registry = new SessionAgentRegistry();
        UUID sessionId = UUID.randomUUID();
        List<VetoRequest> childRequests = new ArrayList<>();
        List<VetoAgent> childAgents = new ArrayList<>();
        AtomicInteger childTurn = new AtomicInteger();
        UniformLLMCaller childCaller =
                request -> {
                    childRequests.add(request);
                    var active = registry.agents(sessionId);
                    assertEquals(2, active.size());
                    assertEquals(
                            AgentState.WAITING,
                            active.stream()
                                    .filter(entry -> entry.parentAgentId() == null)
                                    .findFirst()
                                    .orElseThrow()
                                    .agent()
                                    .state());
                    var child =
                            active.stream()
                                    .filter(entry -> entry.parentAgentId() != null)
                                    .findFirst()
                                    .orElseThrow();
                    assertTrue(child.parentCallId() != null);
                    if (childAgents.isEmpty()) childAgents.add(child.agent());
                    assertFalse(child.agent().userInteractionEnabled());
                    return switch (childTurn.getAndIncrement()) {
                        case 0 -> call("fetch_page", Map.of());
                        case 1 -> call("read_sections", Map.of("ids", List.of("s1", "s2")));
                        case 2, 3 ->
                                call(
                                        "finish_read",
                                        Map.of(
                                                "outcome",
                                                maxRounds == 3 ? "partial" : "complete",
                                                "answer",
                                                maxRounds == 4 && childTurn.get() == 3 && !guided
                                                        ? "x".repeat(4001)
                                                        : "Timeout is 30 seconds.",
                                                "evidenceIds",
                                                maxRounds == 4 && childTurn.get() == 3 && !guided
                                                        ? Collections.nCopies(15, "s1")
                                                        : List.of("s1"),
                                                "limitations",
                                                maxRounds == 4 && childTurn.get() == 3 && guided
                                                        ? List.of(List.of("Malformed nested entry"))
                                                        : maxRounds == 4 && childTurn.get() == 3
                                                                ? Collections.nCopies(9, "Gap")
                                                                : maxRounds == 3
                                                                        ? List.of(
                                                                                "Other timeout behavior is not established by the inspected evidence.")
                                                                        : List.of()));
                        default -> throw new AssertionError("Reader unexpectedly restarted");
                    };
                };
        var models = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        when(models.resolve("test-owner", ModelTier.LOW))
                .thenReturn(
                        new ModelBinding(
                                ProviderType.DEEPSEEK, "isolated-reader", "reader-key", 0, 2048));
        @NonNull TurnRecordRepository turnRepository = mock();
        WebFetchExecutor reader =
                new WebFetchExecutor(
                        mapper,
                        childCaller,
                        models,
                        new DefaultCapabilityTranslator(mapper),
                        registry,
                        new TurnLogService(turnRepository, mapper),
                        ModelTier.LOW,
                        maxRounds,
                        15,
                        32000,
                        2048);
        var access = mock(ToolDocs.nonNullClass(WebReadCapability.class));
        when(access.fetch(anyLong()))
                .thenReturn(
                        new FetchedPage(
                                URI.create("https://example.com/docs"),
                                200,
                                "text/html",
                                "<main><p>Timeout is 30 seconds.</p><p>RAW_CHILD_PAGE_SENTINEL</p></main>",
                                false,
                                10000));
        when(access.read(anyString()))
                .thenAnswer(
                        invocation -> {
                            String objective = invocation.getArgument(0);
                            if (objective == null)
                                throw new AssertionError("Missing reader objective");
                            return reader.read(objective, access);
                        });
        var network = mock(ToolDocs.nonNullClass(NetworkEgressCapabilityImpl.class));
        when(network.openReader(any())).thenReturn(access);
        var context = mock(ToolDocs.nonNullClass(ApplicationContext.class));
        when(context.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
        ToolEngineImpl engine =
                new ToolEngineImpl(mapper, List.of(new WebFetchTool(network)), context);
        engine.afterSingletonsInstantiated();
        List<VetoRequest> parentRequests = new ArrayList<>();
        AtomicInteger parentTurn = new AtomicInteger();
        UniformLLMCaller parentCaller =
                request -> {
                    parentRequests.add(request);
                    if (parentTurn.getAndIncrement() > 0)
                        return new VetoResponse(null, null, "Timeout is 30 seconds.", null);
                    if (!guided)
                        return new VetoResponse(
                                null,
                                List.of(
                                        new ToolCall(
                                                "web_fetch",
                                                Map.of(
                                                        "url",
                                                        "https://example.com/docs",
                                                        "objective",
                                                        "Find timeout units."))),
                                null,
                                null);
                    try {
                        return new VetoResponse(
                                null,
                                null,
                                null,
                                new VetoResponse.Guide(
                                        mapper.readTree(
                                                """
                        [{"id":"read","label":"Read","type":"tool","tool":"web_fetch","inputs":{"url":"https://example.com/docs","objective":"Find timeout units."},"outputs":{"reading":"content"}},
                         {"id":"answer","label":"Answer","type":"generate","prompt":"Answer from $reading","inputs":{"reading":"$reading"},"outputs":{"answer":"message"}},
                         {"id":"done","label":"Done","type":"STOP","result_binding":"answer"}]
                        """)));
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                };
        HitlRegistry hitl = new HitlRegistry();
        AgentService service = service(engine, parentCaller, mapper, hitl);
        var binding =
                new AgentRunner.LlmBinding(
                        ProviderType.DEEPSEEK,
                        "parent-model",
                        "parent-key",
                        LlmOptions.defaults(),
                        null);
        ReflectionTestUtils.setField(service, "sessionAgents", registry);
        String session = sessionId.toString();
        UUID user = UUID.randomUUID();
        service.getOrCreateAgent(
                session,
                UUID.randomUUID().toString(),
                binding,
                List.of(),
                user,
                "test-owner",
                "D:/IdeaProjects/veto",
                0,
                presentation,
                guided);
        var result =
                service.submit(
                        session,
                        "MAIN_PRIVATE_CONTEXT_SENTINEL: Read the timeout documentation.",
                        binding,
                        Duration.ofSeconds(20),
                        null,
                        prompt -> {
                            VetoOption option =
                                    prompt.options().stream()
                                            .filter(value -> !value.isRefusal())
                                            .findFirst()
                                            .orElseThrow();
                            assertTrue(
                                    hitl.resolveOption(
                                            prompt.agentId(), prompt.callId(), option.name()));
                        });
        assertTrue(result.success(), result.message());
        assertEquals("Timeout is 30 seconds.", result.message());
        assertEquals(maxRounds == 4 ? 4 : 3, childRequests.size());
        var finalRequest = childRequests.getLast();
        assertEquals(maxRounds <= 4 ? 1 : 4, finalRequest.tools().size());
        if (maxRounds <= 4) {
            assertEquals("finish_read", finalRequest.tools().getFirst().name());
            assertTrue(
                    finalRequest
                            .messages()
                            .getLast()
                            .content()
                            .contains("final allowed model call"));
        }
        if (maxRounds == 4) {
            assertEquals(
                    List.of("finish_read"),
                    childRequests.get(2).tools().stream().map(tool -> tool.name()).toList());
            assertTrue(
                    mapper.writeValueAsString(finalRequest)
                            .contains(
                                    guided
                                            ? "schema violation"
                                            : "answer exceeds 4000 characters"));
            if (!guided) {
                String correction = mapper.writeValueAsString(finalRequest);
                assertTrue(correction.contains("evidenceIds must contain at most 8"));
                assertTrue(correction.contains("limitations must contain at most 8"));
            }
        }
        var childHistory = childAgents.getFirst().history();
        assertFalse(
                childHistory.stream().anyMatch(turn -> turn.type() == TurnType.ASSISTANT_RESPONSE));
        var finishCall =
                childHistory.stream()
                        .filter(
                                turn ->
                                        turn.type() == TurnType.TOOL_CALL
                                                && "finish_read"
                                                        .equals(turn.payload().get("tool_name")))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                childHistory.stream()
                        .anyMatch(
                                turn ->
                                        turn.type() == TurnType.TOOL_RESPONSE
                                                && java.util.Objects.equals(
                                                        finishCall.payload().get("call_id"),
                                                        turn.payload().get("call_id"))));
        String childSystem = childRequests.getFirst().systemPrompt();
        assertTrue(childSystem.contains("## Operating Contract"));
        assertTrue(childSystem.contains("## Task Instructions"));
        assertTrue(childSystem.contains("## Your Tools"));
        assertTrue(childSystem.contains("## Response Protocol"));
        for (String name : List.of("fetch_page", "find_sections", "read_sections", "finish_read")) {
            assertTrue(childSystem.contains("### `" + name + "`"));
        }
        assertFalse(childSystem.contains("### `run_command`"));
        assertFalse(childSystem.contains("## Workspace"));
        assertFalse(childSystem.contains("## How to Delegate"));
        assertFalse(childSystem.contains("{{TASK_INSTRUCTIONS}}"));
        assertFalse(childSystem.contains("{{TOOLS}}"));
        ArgumentCaptor<@NonNull TurnRecordEntity> captured = ArgumentCaptor.captor();
        verify(turnRepository, atLeastOnce()).save(captured.capture());
        var savedTurns = captured.getAllValues();
        assertTrue(
                savedTurns.stream()
                        .allMatch(row -> sessionId.toString().equals(row.getSessionId())));
        assertTrue(savedTurns.stream().allMatch(row -> user.toString().equals(row.getUserId())));
        assertEquals(1, savedTurns.stream().map(TurnRecordEntity::getAgentId).distinct().count());
        assertTrue(
                savedTurns.stream()
                        .anyMatch(row -> row.getPayload().contains("RAW_CHILD_PAGE_SENTINEL")));
        assertTrue(savedTurns.stream().anyMatch(row -> row.getType().equals("AGENT_INIT")));
        assertTrue(mapper.writeValueAsString(childRequests).contains("RAW_CHILD_PAGE_SENTINEL"));
        assertFalse(
                mapper.writeValueAsString(childRequests).contains("MAIN_PRIVATE_CONTEXT_SENTINEL"));
        assertTrue(parentRequests.size() >= 2);
        String afterRead = mapper.writeValueAsString(parentRequests.get(1));
        assertTrue(afterRead.contains("Timeout is 30 seconds."));
        assertTrue(afterRead.contains("https://example.com/docs"));
        assertFalse(afterRead.contains("RAW_CHILD_PAGE_SENTINEL"));
        assertFalse(afterRead.contains("CHILD_THOUGHT_SENTINEL"));
        assertFalse(afterRead.contains("Runtime budget:"));
        if (maxRounds == 3) {
            assertTrue(afterRead.contains("partial"));
            assertTrue(afterRead.contains("Other timeout behavior is not established"));
        }
        assertFalse(afterRead.contains("\"toolName\":\"read_sections\""));
        var agent = service.agent(session);
        if (agent == null) throw new AssertionError("Missing parent agent");
        var history = agent.history();
        assertFalse(mapper.writeValueAsString(history).contains("RAW_CHILD_PAGE_SENTINEL"));

        AgentService resumed = service(engine, parentCaller, mapper, new HitlRegistry());
        String resumedSession = UUID.randomUUID().toString();
        resumed.getOrCreateAgent(
                resumedSession,
                UUID.randomUUID().toString(),
                binding,
                history,
                user,
                "test-owner",
                "D:/IdeaProjects/veto",
                0,
                presentation,
                guided);
        var resumedResult =
                resumed.submit(
                        resumedSession,
                        "Repeat the documented timeout.",
                        binding,
                        Duration.ofSeconds(10));
        assertTrue(resumedResult.success(), resumedResult.message());
        String replayed = mapper.writeValueAsString(parentRequests.getLast());
        assertTrue(replayed.contains("Timeout is 30 seconds."));
        assertFalse(replayed.contains("RAW_CHILD_PAGE_SENTINEL"));
        assertFalse(replayed.contains("CHILD_THOUGHT_SENTINEL"));
        assertEquals(maxRounds == 4 ? 4 : 3, childRequests.size());
        assertEquals(1, registry.agents(sessionId).size());
        service.remove(session);
        resumed.remove(resumedSession);
        assertTrue(registry.agents(sessionId).isEmpty());
        verify(access).close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"session", "task", "approval", "stubborn"})
    void cancellationClosesReaderAndPreservesParentWhenRequested(@NonNull String mode)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SessionAgentRegistry registry = spy(new SessionAgentRegistry());
        doAnswer(
                        invocation -> {
                            assertFalse(
                                    Thread.currentThread().isInterrupted(),
                                    "Reader stop must be able to persist its lifecycle without closing DB sockets");
                            return invocation.callRealMethod();
                        })
                .when(registry)
                .stop(anyString());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch accessClosed = new CountDownLatch(1);
        CountDownLatch approval = new CountDownLatch(1);
        CountDownLatch releaseChild = new CountDownLatch(1);
        AtomicReference<Thread> readerThread = new AtomicReference<>();
        UniformLLMCaller childCaller =
                request -> {
                    readerThread.set(Thread.currentThread());
                    entered.countDown();
                    while (releaseChild.getCount() != 0) {
                        try {
                            releaseChild.await();
                        } catch (InterruptedException error) {
                            interrupted.countDown();
                            if (!mode.equals("stubborn")) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("Reader canceled", error);
                            }
                        }
                    }
                    throw new IllegalStateException("Reader released");
                };
        var models = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        when(models.resolve("test-owner", ModelTier.LOW))
                .thenReturn(new ModelBinding(ProviderType.DEEPSEEK, "reader", "key", 0, 2048));
        @NonNull TurnRecordRepository turnRepository = mock();
        WebFetchExecutor reader =
                new WebFetchExecutor(
                        mapper,
                        childCaller,
                        models,
                        new DefaultCapabilityTranslator(mapper),
                        registry,
                        new TurnLogService(turnRepository, mapper),
                        ModelTier.LOW,
                        5,
                        30,
                        32000,
                        2048);
        var access = mock(ToolDocs.nonNullClass(WebReadCapability.class));
        when(access.read(anyString()))
                .thenAnswer(
                        invocation -> {
                            String objective = invocation.getArgument(0);
                            if (objective == null) throw new AssertionError("Missing objective");
                            return reader.read(objective, access);
                        });
        doAnswer(
                        invocation -> {
                            accessClosed.countDown();
                            return null;
                        })
                .when(access)
                .close();
        var network = mock(ToolDocs.nonNullClass(NetworkEgressCapabilityImpl.class));
        when(network.openReader(any())).thenReturn(access);
        var context = mock(ToolDocs.nonNullClass(ApplicationContext.class));
        when(context.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
        ToolEngineImpl engine =
                new ToolEngineImpl(mapper, List.of(new WebFetchTool(network)), context);
        engine.afterSingletonsInstantiated();
        AtomicInteger parentCalls = new AtomicInteger();
        UniformLLMCaller parentCaller =
                request ->
                        parentCalls.incrementAndGet() > 1
                                ? new VetoResponse(null, null, "Next task complete", null)
                                : call(
                                        "web_fetch",
                                        Map.of(
                                                "url",
                                                "https://example.com/docs",
                                                "objective",
                                                "Find timeout."));
        HitlRegistry hitl = new HitlRegistry();
        AgentService service = service(engine, parentCaller, mapper, hitl);
        ReflectionTestUtils.setField(service, "sessionAgents", registry);
        AtomicInteger persistedCancellations = new AtomicInteger();
        @NonNull TurnLogService parentLog = mock();
        doAnswer(
                        invocation -> {
                            TurnRecord turn = invocation.getArgument(0);
                            if (turn != null && "CANCELLED".equals(turn.payload().get("outcome"))) {
                                assertFalse(
                                        Thread.currentThread().isInterrupted(),
                                        "Cancellation history must be written without a pending interrupt");
                                persistedCancellations.incrementAndGet();
                            }
                            return null;
                        })
                .when(parentLog)
                .log(any(), any(), any(), anyString());
        ReflectionTestUtils.setField(service, "turnLogService", parentLog);
        UUID sessionId = UUID.randomUUID();
        String session = sessionId.toString();
        AgentRunner.LlmBinding binding =
                new AgentRunner.LlmBinding(
                        ProviderType.DEEPSEEK, "parent", "key", LlmOptions.defaults(), null);
        service.getOrCreateAgent(
                session,
                UUID.randomUUID().toString(),
                binding,
                List.of(),
                UUID.randomUUID(),
                "test-owner",
                "D:/IdeaProjects/veto",
                0,
                ToolResultPresentationMode.BASIC,
                false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread submission =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        service.submit(
                                                session,
                                                "Read the page",
                                                binding,
                                                Duration.ofSeconds(20),
                                                null,
                                                prompt -> {
                                                    approval.countDown();
                                                    if (mode.equals("approval")) return;
                                                    VetoOption option =
                                                            prompt.options().stream()
                                                                    .filter(
                                                                            value ->
                                                                                    !value
                                                                                            .isRefusal())
                                                                    .findFirst()
                                                                    .orElseThrow();
                                                    assertTrue(
                                                            hitl.resolveOption(
                                                                    prompt.agentId(),
                                                                    prompt.callId(),
                                                                    option.name()));
                                                });
                                    } catch (Throwable error) {
                                        failure.set(error);
                                    }
                                });
        try {
            assertTrue(approval.await(5, TimeUnit.SECONDS));
            var parent = service.agent(session);
            if (parent == null) throw new AssertionError("Parent missing");
            if (mode.equals("approval")) {
                assertTrue(parent.cancelTask(parent.result(), Duration.ofSeconds(3)));
                submission.join(3000);
                assertFalse(submission.isAlive());
                assertFalse(parent.result().get().success());
                assertTrue(hitl.pendingFor(parent.id()).isEmpty());
                assertEquals(1, entered.getCount(), "Unapproved reader must never start");
                verify(network, never()).openReader(any());
                parent.submit("Next task");
                assertTrue(parent.await(Duration.ofSeconds(3)).success());
                return;
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(2, registry.agents(sessionId).size());
            var child =
                    registry.agents(sessionId).stream()
                            .filter(entry -> entry.parentAgentId() != null)
                            .findFirst()
                            .orElseThrow()
                            .agent();
            if (mode.equals("session")) service.remove(session);
            else {
                var task = parent.result();
                if (mode.equals("stubborn")) {
                    assertFalse(parent.cancelTask(task, Duration.ofMillis(100)));
                    assertTrue(interrupted.await(3, TimeUnit.SECONDS));
                    assertEquals(
                            1,
                            accessClosed.getCount(),
                            "Reader capability remains owned while child executes");
                    releaseChild.countDown();
                }
                assertTrue(parent.cancelTask(task, Duration.ofSeconds(3)));
                assertFalse(task.get().success());
                assertEquals(1, persistedCancellations.get());
            }
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            submission.join(3000);
            assertFalse(submission.isAlive());
            Thread worker = readerThread.get();
            if (worker == null) throw new AssertionError("Reader was not started");
            worker.join(3000);
            assertFalse(worker.isAlive());
            assertEquals(AgentState.TERMINATED, child.state());
            assertFalse(child.result().get(1, TimeUnit.SECONDS).success());
            assertThrows(IllegalStateException.class, () -> child.submit("Must not restart"));
            if (mode.equals("session")) {
                assertTrue(registry.agents(sessionId).isEmpty());
                assertNull(service.agent(session));
            } else {
                assertNotEquals(AgentState.TERMINATED, parent.state());
                parent.submit("Next task");
                assertTrue(parent.await(Duration.ofSeconds(3)).success());
            }
            assertNull(failure.get());
            // Cancellation completes the submission future before the parent tool unwinds.
            assertTrue(accessClosed.await(3, TimeUnit.SECONDS), "Reader capability was not closed");
            verify(access).close();
        } finally {
            releaseChild.countDown();
            service.remove(session);
            submission.interrupt();
            submission.join(3000);
        }
    }

    private static @NonNull AgentService service(
            @NonNull ToolEngineImpl engine,
            @NonNull UniformLLMCaller caller,
            @NonNull ObjectMapper mapper,
            @NonNull HitlRegistry hitl) {
        PromptCompiler compiler =
                new PromptCompiler(
                        new DefaultCapabilityTranslator(mapper),
                        new SystemPromptResolver(),
                        mapper,
                        "FULL_ACCESS");
        ReflectionTestUtils.setField(compiler, "maxInputTokens", 32000);
        ReflectionTestUtils.setField(compiler, "contextFillRatio", 0.9);
        return new AgentService(
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
                mock(ToolDocs.nonNullClass(BackgroundTaskManager.class)));
    }

    private static @NonNull VetoResponse call(
            @NonNull String name, @NonNull Map<@NonNull String, Object> args) {
        return new VetoResponse(
                "CHILD_THOUGHT_SENTINEL", List.of(new ToolCall(name, args)), null, null);
    }
}
