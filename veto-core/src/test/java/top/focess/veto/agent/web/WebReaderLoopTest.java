package top.focess.veto.agent.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.AgentState;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.agent.capability.NetworkEgressCapabilityImpl;
import top.focess.veto.agent.capability.WebReadCapability;
import top.focess.veto.agent.tool.CapabilityTestCalls;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolExecutionException;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.ToolDefinition;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.vault.UserContext;

class WebReaderLoopTest {
    private final @NonNull ObjectMapper mapper = new ObjectMapper();
    private final @NonNull WebReadCapability access =
            mock(ToolDocs.nonNullClass(WebReadCapability.class));
    private final @NonNull List<@NonNull VetoRequest> requests = new ArrayList<>();

    @AfterEach
    void clearOwner() {
        UserContext.clear();
    }

    @Test
    void isolatedLoopReturnsOnlySelectedEvidenceAndConfiguredModel() throws Exception {
        WebReadTool tool = tool(script(List.of(fetch(), read(), finish("s1"))), 6, 10);
        String result = execute(tool);
        var json = mapper.readTree(result);
        assertEquals("complete", json.path("outcome").asText());
        assertEquals(
                "The timeout is 30 seconds.", json.path("evidence").get(0).path("quote").asText());
        assertEquals("reader-model", json.path("execution").path("model").asText());
        assertFalse(result.contains("UNRELATED_PAGE_BODY"));
        VetoRequest first = requests.getFirst();
        assertEquals("Find timeout units.", first.userPrompt());
        assertEquals(2, first.messages().size());
        assertEquals("Find timeout units.", first.messages().get(1).content());
        assertEquals(
                Set.of("fetch_page", "read_sections", "find_sections", "finish_read"),
                Set.copyOf(first.tools().stream().map(ToolDefinition::name).toList()));
        assertFalse(first.systemPrompt().contains("UNRELATED_PAGE_BODY"));
        assertEquals("test-owner", UserContext.get());
        verify(access).fetch(anyLong());
        verify(access).close();
    }

    @Test
    void findsLateEvidenceInALongMultilingualDocumentWithoutAnUnboundedOutline() throws Exception {
        StringBuilder html = new StringBuilder("<main>");
        for (int index = 1; index <= 150; index++) {
            html.append("<h2>").append("配置说明と設定ガイド".repeat(14)).append(index).append("</h2><p>");
            html.append(index == 150 ? "timeout 的单位是秒。The timeout is 30 seconds." : "此章节仅介绍其他设置。");
            html.append("</p>");
        }
        html.append("</main>");
        WebReadTool tool =
                tool(
                        script(
                                List.of(
                                        fetch(),
                                        call("find_sections", Map.of("query", "timeout")),
                                        call("read_sections", Map.of("ids", List.of("s300"))),
                                        finish("s300"))),
                        6,
                        10);
        when(access.fetch(anyLong()))
                .thenReturn(
                        new FetchedPage(
                                URI.create("https://example.com/docs"),
                                200,
                                "text/html",
                                html.toString(),
                                false,
                                100000));

        var result = mapper.readTree(execute(tool));
        assertEquals("complete", result.path("outcome").asText());
        assertEquals(
                "timeout 的单位是秒。The timeout is 30 seconds.",
                result.path("evidence").get(0).path("quote").asText());
        var initialOutline = mapper.readTree(requests.get(1).messages().getLast().content());
        assertEquals(300, initialOutline.path("segmentCount").asInt());
        assertEquals(24, initialOutline.path("outline").size());
        assertTrue(requests.get(2).messages().getLast().content().contains("s300"));
        for (VetoRequest request : requests) {
            var schema = request.responseSchema();
            if (schema == null) throw new AssertionError("Missing reader response schema");
            int bytes =
                    request.messages().stream()
                            .mapToInt(
                                    message -> {
                                        String args = message.toolArgs();
                                        return message.content()
                                                        .getBytes(StandardCharsets.UTF_8)
                                                        .length
                                                + (args == null
                                                        ? 0
                                                        : args.getBytes(StandardCharsets.UTF_8)
                                                                .length)
                                                + 100;
                                    })
                            .sum();
            assertTrue(
                    bytes
                                    + mapper.writeValueAsBytes(request.tools()).length
                                    + mapper.writeValueAsBytes(schema).length
                            <= 32000,
                    "Each child request stays within its configured input bound");
        }
    }

    @Test
    void oversizedMultilingualReadCanRetryWithoutAuthorizingUnseenEvidence() throws Exception {
        StringBuilder html = new StringBuilder("<main>");
        for (int index = 1; index <= 8; index++) {
            String prefix = index == 1 ? "The timeout is 30 seconds." : "UNSEEN_SECTION_" + index;
            html.append("<p>")
                    .append(prefix)
                    .append("中".repeat(1200 - prefix.length()))
                    .append("</p>");
        }
        html.append("</main>");
        WebReadTool tool =
                tool(
                        script(
                                List.of(
                                        fetch(),
                                        call(
                                                "read_sections",
                                                Map.of(
                                                        "ids",
                                                        List.of(
                                                                "s1", "s2", "s3", "s4", "s5", "s6",
                                                                "s7", "s8"))),
                                        finish("s8"),
                                        read(),
                                        finish("s1"))),
                        7,
                        10,
                        32000);
        when(access.fetch(anyLong()))
                .thenReturn(
                        new FetchedPage(
                                URI.create("https://example.com/docs"),
                                200,
                                "text/html",
                                html.toString(),
                                false,
                                20000));

        var result = mapper.readTree(execute(tool));
        assertEquals("complete", result.path("outcome").asText());
        assertTrue(
                result.path("evidence")
                        .get(0)
                        .path("quote")
                        .asText()
                        .startsWith("The timeout is 30 seconds."));
        assertTrue(requests.get(2).messages().getLast().content().toLowerCase().contains("fewer"));
        assertTrue(
                requests.get(3)
                        .messages()
                        .getLast()
                        .content()
                        .contains("Evidence must reference a read segment"));
        mapper.registerModule(new JavaTimeModule());
        assertFalse(mapper.writeValueAsString(requests).contains("UNSEEN_SECTION_8"));
        assertEquals(5, requests.size());
    }

    @Test
    void excludedOperationsAreRejectedAndTheReaderCanRecover() throws Exception {
        List<String> excluded =
                List.of(
                        "view_file",
                        "write_to_file",
                        "run_command",
                        "run_task",
                        "view_task",
                        "recall_memory",
                        "write_memory",
                        "load_skill",
                        "web_search",
                        "web_fetch",
                        "web_read",
                        "create_group",
                        "create_node",
                        "post_message",
                        "ask_user",
                        "mcp_remote",
                        "think");
        for (String name : excluded) {
            reset(access);
            requests.clear();
            String result =
                    execute(
                            tool(
                                    script(
                                            List.of(
                                                    fetch(),
                                                    call(
                                                            name,
                                                            Map.of(
                                                                    "url",
                                                                    "https://evil.example/steal")),
                                                    read(),
                                                    finish("s1"))),
                                    6,
                                    20));
            assertEquals("complete", mapper.readTree(result).path("outcome").asText(), name);
            assertTrue(
                    requests.get(2).messages().stream()
                            .anyMatch(
                                    message ->
                                            message.content()
                                                            .contains(
                                                                    "not in this turn's tool catalog")
                                                    && message.content().contains(name)),
                    name);
            verify(access).fetch(anyLong());
            verify(access).close();
            verify(access).read("Find timeout units.");
            verify(access).bindReader(anyString());
            verifyNoMoreInteractions(access);
        }
    }

    @Test
    void fabricatedEvidenceIsRejectedBeforeReturningAndCanBeRepaired() throws Exception {
        String result =
                execute(
                        tool(
                                script(
                                        List.of(
                                                fetch(),
                                                read(),
                                                finish("nonexistent"),
                                                finish("s1"))),
                                6,
                                10));
        assertEquals("complete", mapper.readTree(result).path("outcome").asText());
        assertTrue(
                requests.getLast().messages().stream()
                        .anyMatch(
                                message ->
                                        message.content()
                                                .contains(
                                                        "Evidence must reference a read segment")));
        assertEquals(4, requests.size());
    }

    @Test
    void modelFailureIsAnErrorNotANegativeResearchFinding() {
        WebReadTool tool =
                tool(
                        request -> {
                            throw new IllegalStateException("provider secret must not escape");
                        },
                        4,
                        10);
        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class), () -> execute(tool));
        String message = error.content();
        assertEquals("READER_MODEL", error.errorCode());
        assertFalse(message.contains("provider secret"));
        assertFalse(message.contains("not_found"));
        verify(access).close();
        verify(access, never()).fetch(anyLong());
    }

    @Test
    void modelTimeoutInterruptsTheWorkerAndClosesTheCapability() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        WebReadTool tool = tool(blocking(interrupted, worker, new CountDownLatch(1)), 4, 1);
        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class), () -> execute(tool));
        String message = error.content();
        assertTrue(message.contains("time budget"));
        assertTrue(interrupted.await(3, TimeUnit.SECONDS));
        Thread thread = worker.get();
        assertNotNull(thread);
        thread.join(3000);
        assertFalse(thread.isAlive());
        assertEquals("test-owner", UserContext.get());
        verify(access).close();
    }

    @Test
    void parentCancellationInterruptsWorkerAndReturnsNoLateResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<String> result = new AtomicReference<>();
        WebReadTool tool = tool(blocking(interrupted, worker, entered), 4, 20);
        Thread parent =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        result.set(execute(tool));
                                    } catch (Exception error) {
                                        failure.set(error);
                                    } finally {
                                        UserContext.clear();
                                    }
                                });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            parent.interrupt();
            parent.join(3000);
            assertFalse(parent.isAlive());
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertInstanceOf(ToolDocs.nonNullClass(ToolExecutionException.class), failure.get());
            assertNull(result.get());
            Thread thread = worker.get();
            assertNotNull(thread);
            thread.join(3000);
            assertFalse(thread.isAlive());
            assertNull(UserContext.get());
            verify(access).close();
        } finally {
            parent.interrupt();
        }
    }

    private @NonNull UniformLLMCaller blocking(
            @NonNull CountDownLatch interrupted,
            @NonNull AtomicReference<Thread> worker,
            @NonNull CountDownLatch entered) {
        return request -> {
            assertEquals("test-owner", UserContext.get());
            worker.set(Thread.currentThread());
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException error) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", error);
            }
            return finish("s1");
        };
    }

    private @NonNull UniformLLMCaller script(@NonNull List<@NonNull VetoResponse> turns) {
        AtomicInteger index = new AtomicInteger();
        return request -> {
            assertEquals("test-owner", UserContext.get());
            requests.add(request);
            return turns.get(index.getAndIncrement());
        };
    }

    private @NonNull WebReadTool tool(@NonNull UniformLLMCaller caller, int rounds, int timeout) {
        return tool(caller, rounds, timeout, 32000);
    }

    private @NonNull WebReadTool tool(
            @NonNull UniformLLMCaller caller, int rounds, int timeout, int maxInputTokens) {
        var network = mock(ToolDocs.nonNullClass(NetworkEgressCapabilityImpl.class));
        when(network.openReader(any())).thenReturn(access);
        when(access.fetch(anyLong()))
                .thenReturn(
                        new FetchedPage(
                                URI.create("https://example.com/docs"),
                                200,
                                "text/html",
                                "<main><p>The timeout is 30 seconds.</p><p>UNRELATED_PAGE_BODY</p></main>",
                                false,
                                10000));
        var models = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        when(models.resolve("test-owner", ModelTier.LOW))
                .thenReturn(
                        new ModelBinding(
                                ProviderType.DEEPSEEK,
                                "reader-model",
                                "reader-credential",
                                0,
                                2048));
        SessionAgentRegistry registry = new SessionAgentRegistry();
        WebReader reader =
                new WebReader(
                        mapper,
                        caller,
                        models,
                        new DefaultCapabilityTranslator(mapper),
                        registry,
                        ModelTier.LOW,
                        rounds,
                        timeout,
                        maxInputTokens,
                        2048);
        when(access.read(anyString()))
                .thenAnswer(
                        invocation -> {
                            String objective = invocation.getArgument(0);
                            if (objective == null) throw new AssertionError("Missing objective");
                            var context = ToolCallContextHolder.get();
                            if (context == null) throw new AssertionError("Missing parent context");
                            var sessionId = context.sessionId();
                            if (sessionId == null)
                                throw new AssertionError("Missing parent session");
                            var parent = mock(ToolDocs.nonNullClass(VetoAgent.class));
                            when(parent.id()).thenReturn(context.agentId());
                            when(parent.state()).thenReturn(AgentState.RUNNING);
                            registry.register(sessionId, parent);
                            try {
                                return reader.read(objective, access);
                            } finally {
                                assertEquals(1, registry.agents(sessionId).size());
                                registry.stopSession(sessionId);
                            }
                        });
        return new WebReadTool(network);
    }

    private @NonNull String execute(@NonNull WebReadTool tool) throws Exception {
        UserContext.set("test-owner");
        return CapabilityTestCalls.execute(
                tool, new WebReadTool.Args("https://example.com/docs", "Find timeout units."));
    }

    private static @NonNull VetoResponse fetch() {
        return call("fetch_page", Map.of());
    }

    private static @NonNull VetoResponse read() {
        return call("read_sections", Map.of("ids", List.of("s1")));
    }

    private static @NonNull VetoResponse finish(@NonNull String id) {
        return call(
                "finish_read",
                Map.of(
                        "outcome",
                        "complete",
                        "answer",
                        "30 seconds.",
                        "evidenceIds",
                        List.of(id),
                        "limitations",
                        List.of()));
    }

    private static @NonNull VetoResponse call(
            @NonNull String name, @NonNull Map<@NonNull String, Object> args) {
        return new VetoResponse(null, List.of(new ToolCall(name, args)), null, null);
    }
}
