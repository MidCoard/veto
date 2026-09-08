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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import top.focess.veto.agent.web.WebReadTool;
import top.focess.veto.agent.web.WebReader;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.sandbox.BackgroundTaskManager;

class WebReadAgentIntegrationTest {
    @ParameterizedTest
    @CsvSource({"BASIC,false", "DETAILED,false", "BASIC,true", "DETAILED,true"})
    void parentContextAndReplayedHistoryContainOnlyTerminalEvidence(
            @NonNull ToolResultPresentationMode presentation, boolean guided) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        SessionAgentRegistry registry = new SessionAgentRegistry();
        UUID sessionId = UUID.randomUUID();
        List<VetoRequest> childRequests = new ArrayList<>();
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
                    return switch (childTurn.getAndIncrement()) {
                        case 0 -> call("fetch_page", Map.of());
                        case 1 -> call("read_sections", Map.of("ids", List.of("s1", "s2")));
                        case 2 ->
                                call(
                                        "finish_read",
                                        Map.of(
                                                "outcome",
                                                "complete",
                                                "answer",
                                                "Timeout is 30 seconds.",
                                                "evidenceIds",
                                                List.of("s1"),
                                                "limitations",
                                                List.of()));
                        default -> throw new AssertionError("Reader unexpectedly restarted");
                    };
                };
        var models = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        when(models.resolve("test-owner", ModelTier.LOW))
                .thenReturn(
                        new ModelBinding(
                                ProviderType.DEEPSEEK, "isolated-reader", "reader-key", 0, 2048));
        WebReader reader =
                new WebReader(
                        mapper,
                        childCaller,
                        models,
                        new DefaultCapabilityTranslator(mapper),
                        registry,
                        ModelTier.LOW,
                        5,
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
                new ToolEngineImpl(mapper, List.of(new WebReadTool(network)), context);
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
                                                "web_read",
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
                        [{"id":"read","label":"Read","type":"tool","tool":"web_read","inputs":{"url":"https://example.com/docs","objective":"Find timeout units."},"outputs":{"reading":"content"}},
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
        assertEquals(3, childRequests.size());
        assertTrue(mapper.writeValueAsString(childRequests).contains("RAW_CHILD_PAGE_SENTINEL"));
        assertFalse(
                mapper.writeValueAsString(childRequests).contains("MAIN_PRIVATE_CONTEXT_SENTINEL"));
        assertTrue(parentRequests.size() >= 2);
        String afterRead = mapper.writeValueAsString(parentRequests.get(1));
        assertTrue(afterRead.contains("Timeout is 30 seconds."));
        assertTrue(afterRead.contains("https://example.com/docs"));
        assertFalse(afterRead.contains("RAW_CHILD_PAGE_SENTINEL"));
        assertFalse(afterRead.contains("CHILD_THOUGHT_SENTINEL"));
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
        assertEquals(3, childRequests.size());
        assertEquals(1, registry.agents(sessionId).size());
        service.remove(session);
        resumed.remove(resumedSession);
        assertTrue(registry.agents(sessionId).isEmpty());
        verify(access).close();
    }

    @Test
    void removingSessionInterruptsItsBlockedReaderAndClosesItsCapability() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SessionAgentRegistry registry = new SessionAgentRegistry();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicReference<Thread> readerThread = new AtomicReference<>();
        UniformLLMCaller childCaller =
                request -> {
                    readerThread.set(Thread.currentThread());
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException error) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Reader canceled", error);
                    }
                    throw new AssertionError("Blocking reader unexpectedly resumed");
                };
        var models = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        when(models.resolve("test-owner", ModelTier.LOW))
                .thenReturn(new ModelBinding(ProviderType.DEEPSEEK, "reader", "key", 0, 2048));
        WebReader reader =
                new WebReader(
                        mapper,
                        childCaller,
                        models,
                        new DefaultCapabilityTranslator(mapper),
                        registry,
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
        var network = mock(ToolDocs.nonNullClass(NetworkEgressCapabilityImpl.class));
        when(network.openReader(any())).thenReturn(access);
        var context = mock(ToolDocs.nonNullClass(ApplicationContext.class));
        when(context.getBeansOfType(AgentTool.class)).thenReturn(Map.of());
        ToolEngineImpl engine =
                new ToolEngineImpl(mapper, List.of(new WebReadTool(network)), context);
        engine.afterSingletonsInstantiated();
        UniformLLMCaller parentCaller =
                request ->
                        call(
                                "web_read",
                                Map.of(
                                        "url",
                                        "https://example.com/docs",
                                        "objective",
                                        "Find timeout."));
        HitlRegistry hitl = new HitlRegistry();
        AgentService service = service(engine, parentCaller, mapper, hitl);
        ReflectionTestUtils.setField(service, "sessionAgents", registry);
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
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(2, registry.agents(sessionId).size());
            var child =
                    registry.agents(sessionId).stream()
                            .filter(entry -> entry.parentAgentId() != null)
                            .findFirst()
                            .orElseThrow()
                            .agent();
            service.remove(session);
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
            assertTrue(registry.agents(sessionId).isEmpty());
            assertNull(service.agent(session));
            assertNull(failure.get());
            verify(access).close();
        } finally {
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
