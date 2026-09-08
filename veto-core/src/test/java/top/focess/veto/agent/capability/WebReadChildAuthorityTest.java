package top.focess.veto.agent.capability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.AgentState;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.CapabilityTestCalls;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.agent.web.FetchedPage;
import top.focess.veto.agent.web.SearchProvider;
import top.focess.veto.agent.web.WebReadTool;
import top.focess.veto.agent.web.WebReader;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.vault.UserContext;

class WebReadChildAuthorityTest {
    @AfterEach
    void clearContext() {
        ToolCallContextHolder.clear();
        UserContext.clear();
    }

    @Test
    void realNetworkFetchRunsUnderDistinctChildPermitThroughAgentRunner() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        server.createContext(
                "/approved",
                exchange -> {
                    hits.incrementAndGet();
                    byte[] body = "Timeout is 30 seconds.".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        try {
            ObjectMapper mapper = new ObjectMapper();
            AtomicInteger modelCalls = new AtomicInteger();
            UniformLLMCaller caller =
                    request -> {
                        assertNull(
                                ToolCallContextHolder.get(),
                                "Model dispatch must have no tool execution permit");
                        assertEquals("test-owner", UserContext.get());
                        boolean throughAgentRunner =
                                StackWalker.getInstance()
                                        .walk(
                                                frames ->
                                                        frames.anyMatch(
                                                                frame ->
                                                                        frame.getClassName()
                                                                                        .equals(
                                                                                                ToolDocs
                                                                                                        .nonNullClass(
                                                                                                                AgentRunner
                                                                                                                        .class)
                                                                                                        .getName())
                                                                                && frame.getMethodName()
                                                                                        .equals(
                                                                                                "callModel")));
                        assertTrue(throughAgentRunner);
                        return switch (modelCalls.getAndIncrement()) {
                            case 0 -> call("fetch_page", Map.of());
                            case 1 -> call("read_sections", Map.of("ids", List.of("s1")));
                            case 2 ->
                                    call(
                                            "finish_read",
                                            Map.of(
                                                    "outcome",
                                                    "complete",
                                                    "answer",
                                                    "30 seconds.",
                                                    "evidenceIds",
                                                    List.of("s1"),
                                                    "limitations",
                                                    List.of()));
                            default ->
                                    throw new AssertionError(
                                            "No extra model call after finish_read");
                        };
                    };
            var models = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
            when(models.resolve("test-owner", ModelTier.LOW))
                    .thenReturn(
                            new ModelBinding(
                                    ProviderType.DEEPSEEK, "reader", "reader-key", 0, 2048));
            SessionAgentRegistry registry = new SessionAgentRegistry();
            WebReader reader =
                    new WebReader(
                            mapper,
                            caller,
                            models,
                            new DefaultCapabilityTranslator(mapper),
                            registry,
                            ModelTier.LOW,
                            6,
                            15,
                            32000,
                            2048);
            var network =
                    spy(
                            new NetworkEgressCapabilityImpl(
                                    mock(ToolDocs.nonNullClass(SearchProvider.class)),
                                    reader,
                                    5,
                                    10000,
                                    true));
            AtomicReference<ToolCallContext> parent = new AtomicReference<>();
            AtomicReference<ToolCallContext> child = new AtomicReference<>();
            AtomicReference<WebReadCapability> captured = new AtomicReference<>();
            doAnswer(
                            invocation -> {
                                ToolCallContext parentContext = ToolCallContextHolder.get();
                                if (parentContext == null)
                                    throw new AssertionError("Missing parent call context");
                                parent.set(parentContext);
                                var session = parentContext.sessionId();
                                if (session == null) throw new AssertionError("Missing session");
                                var parentAgent = mock(ToolDocs.nonNullClass(VetoAgent.class));
                                when(parentAgent.id()).thenReturn(parentContext.agentId());
                                when(parentAgent.state()).thenReturn(AgentState.RUNNING);
                                registry.register(session, parentAgent);
                                WebReadCapability original =
                                        (WebReadCapability) invocation.callRealMethod();
                                if (original == null)
                                    throw new AssertionError("Missing capability");
                                WebReadCapability access = spy(original);
                                captured.set(access);
                                doAnswer(
                                                fetch -> {
                                                    ToolCallContext childContext =
                                                            ToolCallContextHolder.get();
                                                    if (childContext != null)
                                                        child.set(childContext);
                                                    return fetch.callRealMethod();
                                                })
                                        .when(access)
                                        .fetch(anyLong());
                                return access;
                            })
                    .when(network)
                    .openReader(any());
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/approved");
            UserContext.set("test-owner");
            String result =
                    CapabilityTestCalls.execute(
                            new WebReadTool(network),
                            new WebReadTool.Args(url.toString(), "Find timeout units."));
            assertEquals("complete", mapper.readTree(result).path("outcome").asText());
            assertEquals(
                    url.toString(),
                    mapper.readTree(result).path("evidence").get(0).path("url").asText());
            assertEquals(1, hits.get());
            assertEquals(3, modelCalls.get());
            ToolCallContext parentScope = parent.get();
            ToolCallContext childScope = child.get();
            assertNotNull(parentScope);
            assertNotNull(childScope);
            assertNotEquals(parentScope.agentId(), childScope.agentId());
            assertNotEquals(
                    parentScope.executionPermit().callId(), childScope.executionPermit().callId());
            assertEquals("fetch_page", childScope.executionPermit().toolName());
            assertEquals(parentScope.userId(), childScope.userId());
            assertEquals(parentScope.owner(), childScope.owner());
            assertEquals(parentScope.sessionId(), childScope.sessionId());
            WebReadCapability access = captured.get();
            assertNotNull(access);
            assertThrows(SecurityException.class, () -> access.fetch(Long.MAX_VALUE));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void childBindingRejectsRebindingAndWrongIdentityEvenForCachedContent() {
        UUID user = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        ToolCallContext parent = install("parent", user, "owner", session, "web_read");
        AtomicInteger fetches = new AtomicInteger();
        WebReadCapability access =
                new WebReadCapability(
                        deadline -> {
                            fetches.incrementAndGet();
                            return new FetchedPage(
                                    URI.create("https://example.com/approved"),
                                    200,
                                    "text/plain",
                                    "source",
                                    false,
                                    100);
                        },
                        parent,
                        mock(ToolDocs.nonNullClass(WebReader.class)));
        access.bindReader("child");
        assertThrows(SecurityException.class, () -> access.bindReader("replacement"));
        install("child", user, "owner", session, "fetch_page");
        assertEquals("source", access.fetch(Long.MAX_VALUE).content());
        for (ToolCallContext wrong :
                List.of(
                        scope("wrong-child", user, "owner", session, "fetch_page"),
                        scope("child", UUID.randomUUID(), "owner", session, "fetch_page"),
                        scope("child", user, "other-owner", session, "fetch_page"),
                        scope("child", user, "owner", UUID.randomUUID(), "fetch_page"),
                        scope("child", user, "owner", session, "web_fetch"),
                        scope("parent", user, "owner", session, "web_read"))) {
            activate(wrong);
            assertThrows(SecurityException.class, () -> access.fetch(Long.MAX_VALUE));
        }
        install("child", user, "owner", session, "fetch_page");
        access.close();
        assertThrows(SecurityException.class, () -> access.fetch(Long.MAX_VALUE));
        assertEquals(1, fetches.get());
    }

    private static @NonNull ToolCallContext install(
            @NonNull String agent,
            @NonNull UUID user,
            @NonNull String owner,
            @NonNull UUID session,
            @NonNull String tool) {
        ToolCallContext context = scope(agent, user, owner, session, tool);
        activate(context);
        return context;
    }

    private static void activate(@NonNull ToolCallContext context) {
        ToolCallContextHolder.set(context);
        ReflectionTestUtils.invokeMethod(
                ToolCallContextHolder.class,
                "setCurrentCallId",
                context.executionPermit().callId());
    }

    private static @NonNull ToolCallContext scope(
            @NonNull String agent,
            @NonNull UUID user,
            @NonNull String owner,
            @NonNull UUID session,
            @NonNull String tool) {
        ToolExecutionPermit base = ToolExecutionPermit.empty();
        ToolExecutionPermit permit =
                new ToolExecutionPermit(
                                new ToolCall(tool, Map.of()),
                                ToolCapability.NETWORK_EGRESS,
                                null,
                                null,
                                base.filesystemPaths(),
                                base.workspaceRoots(),
                                base.executionRoot(),
                                base.deployerPolicy(),
                                base.protectedPaths(),
                                base.taskBinding())
                        .withCaller(agent, user, null, owner, session);
        return new ToolCallContext(
                agent, user, null, owner, session, ToolResultPresentationMode.BASIC, false, permit);
    }

    private static @NonNull VetoResponse call(
            @NonNull String name, @NonNull Map<@NonNull String, Object> args) {
        return new VetoResponse(null, List.of(new ToolCall(name, args)), null, null);
    }
}
