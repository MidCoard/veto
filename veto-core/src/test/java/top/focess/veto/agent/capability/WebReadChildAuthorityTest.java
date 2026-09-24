package top.focess.veto.agent.capability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.AgentRunner;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.VetoAgent;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.CapabilityTestCalls;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.agent.web.ReaderTestHarness;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.http.ApprovedHttpDestination;
import top.focess.veto.api.http.HttpDocument;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.plugin.agent.IsolatedAgent;
import top.focess.veto.builtin.web.ReaderConfig;
import top.focess.veto.builtin.web.WebFetchTool;
import top.focess.veto.builtin.web.WebReadSession;
import top.focess.veto.builtin.workspace.WriteToFileTool;
import top.focess.veto.integration.plugins.IsolatedExecutions;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.memory.TurnLogService;
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
                                                                                                "run")));
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
            var reader =
                    ReaderTestHarness.create(
                            mapper,
                            caller,
                            models,
                            new DefaultCapabilityTranslator(mapper),
                            registry,
                            new TurnLogService(null, mapper),
                            6,
                            15,
                            32000,
                            2048,
                            () -> {});
            var network = spy(new NetworkEgressCapabilityImpl(5, 10000, true));
            AtomicReference<ToolCallContext> parent = new AtomicReference<>();
            AtomicReference<ToolCallContext> child = new AtomicReference<>();
            AtomicReference<ApprovedHttpDestination> captured = new AtomicReference<>();
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
                                ApprovedHttpDestination original =
                                        (ApprovedHttpDestination) invocation.callRealMethod();
                                if (original == null)
                                    throw new AssertionError("Missing capability");
                                ApprovedHttpDestination access = spy(original);
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
                                        .fetch();
                                return access;
                            })
                    .when(network)
                    .openApprovedDestination("url");
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/approved");
            UserContext.set("test-owner");
            String result =
                    CapabilityTestCalls.execute(
                            new WebFetchTool(reader, network),
                            new WebFetchTool.Args(url.toString(), "Find timeout units."));
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
            ApprovedHttpDestination access = captured.get();
            assertNotNull(access);
            assertThrows(SecurityException.class, () -> access.fetch());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void childBindingRejectsRebindingAndWrongIdentityEvenForCachedContent() {
        UUID user = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        ToolCallContext parent = install("parent", user, "owner", session, "web_fetch");
        AtomicInteger fetches = new AtomicInteger();
        var access =
                new HttpDestinationGrant(
                        deadline -> {
                            fetches.incrementAndGet();
                            return new HttpDocument(
                                    URI.create("https://example.com/approved"),
                                    200,
                                    "text/plain",
                                    "source",
                                    false,
                                    100);
                        },
                        parent);
        var mapper = new ObjectMapper();
        var registry = new SessionAgentRegistry();
        var parentAgent = mock(ToolDocs.nonNullClass(VetoAgent.class));
        when(parentAgent.id()).thenReturn("parent");
        when(parentAgent.state()).thenReturn(AgentState.RUNNING);
        registry.register(session, parentAgent);
        var models = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        when(models.resolve("owner", ModelTier.LOW))
                .thenReturn(new ModelBinding(ProviderType.DEEPSEEK, "reader", "key", 0, 2048));
        var executions =
                new IsolatedExecutions(
                        mapper,
                        request -> {
                            throw new AssertionError("No model requested");
                        },
                        models,
                        new DefaultCapabilityTranslator(mapper),
                        registry,
                        new TurnLogService(null, mapper),
                        new IngressDefense(),
                        128,
                        600,
                        1048576,
                        65536);
        assertThrows(
                SecurityException.class,
                () ->
                        access.bind(
                                mock(ToolDocs.nonNullClass(IsolatedAgent.Runtime.class)),
                                "fetch_page"));
        var runtime = new AtomicReference<IsolatedAgent.Runtime>();
        var child =
                executions.open(
                        new ReaderConfig(Map.of()).spec(),
                        scope -> {
                            runtime.set(scope);
                            access.bind(scope, "fetch_page");
                            return new WebReadSession(scope, access);
                        },
                        () -> true);
        try {
            var scope = runtime.get();
            if (scope == null) throw new AssertionError();
            assertThrows(SecurityException.class, () -> access.bind(scope, "fetch_page"));
            assertThrows(SecurityException.class, () -> access.publish(child));
            assertThrows(
                    SecurityException.class,
                    () ->
                            executions.open(
                                    new ReaderConfig(Map.of()).spec(),
                                    unused -> {
                                        throw new AssertionError();
                                    },
                                    () -> true));
            String id = child.id();
            install(id, user, "owner", session, "fetch_page");
            assertEquals("source", access.fetch().content());
            var approved = ToolCallContextHolder.get();
            if (approved == null) throw new AssertionError();
            var basePermit = approved.executionPermit();
            var expanded =
                    new ToolExecutionPermit(
                            basePermit.call(),
                            basePermit.capability(),
                            basePermit.remoteServerName(),
                            basePermit.caller(),
                            basePermit.filesystemPaths(),
                            basePermit.workspaceRoots(),
                            basePermit.executionRoot(),
                            basePermit.deployerPolicy(),
                            basePermit.protectedPaths(),
                            basePermit.preparation(),
                            Map.of("url", URI.create("https://evil.example/unapproved")));
            activate(
                    new ToolCallContext(
                            id,
                            user,
                            "owner",
                            session,
                            ToolResultPresentationMode.BASIC,
                            expanded));
            assertThrows(
                    SecurityException.class,
                    () ->
                            new NetworkEgressCapabilityImpl(5, 1000, false)
                                    .openApprovedDestination("url"));
            for (ToolCallContext wrong :
                    List.of(
                            scope("wrong-child", user, "owner", session, "fetch_page"),
                            scope(id, UUID.randomUUID(), "owner", session, "fetch_page"),
                            scope(id, user, "other-owner", session, "fetch_page"),
                            scope(id, user, "owner", UUID.randomUUID(), "fetch_page"),
                            scope(id, user, "owner", session, "web_fetch"),
                            scope("parent", user, "owner", session, "web_fetch"))) {
                activate(wrong);
                assertThrows(SecurityException.class, access::fetch);
            }
            activate(parent);
            child.close();
            assertThrows(SecurityException.class, access::fetch);
            assertThrows(
                    SecurityException.class,
                    () ->
                            executions.open(
                                    new ReaderConfig(Map.of()).spec(),
                                    unused -> {
                                        throw new AssertionError();
                                    },
                                    () -> true));
        } finally {
            activate(parent);
            child.close();
            access.close();
            registry.stopSession(session);
        }
        assertEquals(1, fetches.get());
    }

    @Test
    void grantBindingMustNameAnActualPrivateTool() {
        var parent = install("parent", UUID.randomUUID(), "owner", UUID.randomUUID(), "alias");
        var mapper = new ObjectMapper();
        var models = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        when(models.resolve("owner", ModelTier.LOW))
                .thenReturn(new ModelBinding(ProviderType.DEEPSEEK, "reader", "key", 0, 2048));
        var executions =
                new IsolatedExecutions(
                        mapper,
                        request -> {
                            throw new AssertionError();
                        },
                        models,
                        new DefaultCapabilityTranslator(mapper),
                        new SessionAgentRegistry(),
                        new TurnLogService(null, mapper),
                        new IngressDefense(),
                        128,
                        600,
                        1048576,
                        65536);
        try (var grant =
                new HttpDestinationGrant(
                        deadline -> {
                            throw new AssertionError("No fetch before catalog validation");
                        },
                        parent)) {
            assertThrows(
                    SecurityException.class,
                    () ->
                            executions.open(
                                    new ReaderConfig(Map.of()).spec(),
                                    scope -> {
                                        grant.bind(scope, "invented-operation");
                                        return new WebReadSession(scope, grant);
                                    },
                                    () -> true));
        }
    }

    @Test
    void closingGrantDoesNotWaitForInFlightTransportAndRejectsLateContent() throws Exception {
        var parent = install("parent", UUID.randomUUID(), "owner", UUID.randomUUID(), "alias");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var grant =
                new HttpDestinationGrant(
                        deadline -> {
                            entered.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(failure);
                            }
                            return new HttpDocument(
                                    URI.create("https://example.com"),
                                    200,
                                    "text/plain",
                                    "late source",
                                    false,
                                    100);
                        },
                        parent);
        var pending =
                CompletableFuture.supplyAsync(
                        () -> {
                            activate(parent);
                            try {
                                return grant.fetch();
                            } finally {
                                ToolCallContextHolder.clear();
                            }
                        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            long started = System.nanoTime();
            grant.close();
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
            release.countDown();
            var failure = assertThrows(CompletionException.class, pending::join);
            var cause = failure.getCause();
            if (cause == null) throw new AssertionError("Expected fetch failure cause");
            assertInstanceOf(SecurityException.class, cause);
            assertThrows(SecurityException.class, grant::fetch);
        } finally {
            release.countDown();
            grant.close();
        }
    }

    @Test
    void privateCatalogCannotExpandParentCapability() {
        install("parent", UUID.randomUUID(), "owner", UUID.randomUUID(), "alias");
        var mapper = new ObjectMapper();
        var disposed = new AtomicInteger();
        var models = mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        when(models.resolve("owner", ModelTier.LOW))
                .thenReturn(new ModelBinding(ProviderType.DEEPSEEK, "reader", "key", 0, 2048));
        var registry = new SessionAgentRegistry();
        var executions =
                new IsolatedExecutions(
                        mapper,
                        request -> {
                            throw new AssertionError("No model dispatch");
                        },
                        models,
                        new DefaultCapabilityTranslator(mapper),
                        registry,
                        new TurnLogService(null, mapper),
                        new IngressDefense(),
                        128,
                        600,
                        1048576,
                        65536);
        assertThrows(
                SecurityException.class,
                () ->
                        executions.open(
                                new ReaderConfig(Map.of()).spec(),
                                scope ->
                                        new IsolatedAgent.Tools() {
                                            public @NonNull List<NativeTool<?>> tools() {
                                                return List.of(new WriteToFileTool());
                                            }

                                            public void close() {
                                                disposed.incrementAndGet();
                                            }
                                        },
                                () -> true));
        assertEquals(1, disposed.get());
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
                                base.preparation())
                        .withCaller(agent, user, owner, session);
        return new ToolCallContext(
                agent, user, owner, session, ToolResultPresentationMode.BASIC, permit);
    }

    private static @NonNull VetoResponse call(
            @NonNull String name, @NonNull Map<@NonNull String, Object> args) {
        return new VetoResponse(null, List.of(new ToolCall(name, args)), null);
    }
}
