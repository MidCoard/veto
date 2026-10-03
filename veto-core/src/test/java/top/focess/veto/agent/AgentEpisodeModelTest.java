package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static top.focess.veto.agent.AgentRunnerTest.EPISODE_TIMEOUT;
import static top.focess.veto.agent.AgentRunnerTest.binding;
import static top.focess.veto.agent.AgentRunnerTest.requestIdentity;
import static top.focess.veto.agent.AgentRunnerTest.requireAgent;
import static top.focess.veto.agent.AgentRunnerTest.serviceWith;
import static top.focess.veto.integration.plugins.MonitorTestSupport.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.continuation.RequestContinuationEntity;
import top.focess.veto.agent.continuation.RequestContinuationRepository;
import top.focess.veto.agent.continuation.RequestContinuationStore;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.tool.AgentToolDefinition;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.agent.tool.builtin.FixtureLoopTool;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.llm.ChatMessage;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.LlmSystemUsage;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.llm.exceptions.LlmException;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.agent.IsolatedAgent;
import top.focess.veto.api.plugin.contract.AgentConfiguration;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.builtin.group.GroupRegistry;
import top.focess.veto.builtin.monitor.MonitorEntity;
import top.focess.veto.builtin.monitor.MonitorRecord;
import top.focess.veto.builtin.monitor.MonitorRecord.ActivationState;
import top.focess.veto.builtin.monitor.MonitorRepository;
import top.focess.veto.builtin.monitor.MonitorService;
import top.focess.veto.builtin.response.CitationResponsePolicy;
import top.focess.veto.event.EventManager;
import top.focess.veto.integration.plugins.PluginTestSupport;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.memory.TurnRecordEntity;
import top.focess.veto.memory.TurnRecordRepository;
import top.focess.veto.model.AgentEntity;
import top.focess.veto.model.AgentInstanceRepository;
import top.focess.veto.model.AgentPatternRepository;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.model.tier.ModelBinding;
import top.focess.veto.model.tier.ModelTier;
import top.focess.veto.model.tier.ModelTierRegistry;
import top.focess.veto.session.SessionHistoryLoader;
import top.focess.veto.session.SessionService;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.UserContext;

/**
 * Exercises episode budgets, recovered history, and provider response delivery in the agent loop.
 */
class AgentEpisodeModelTest {
    @ParameterizedTest
    @CsvSource({
        "0,false,true",
        "1,false,true",
        "1,false,false",
        "2,true,true",
        "2,true,false",
        "4,true,true",
        "4,true,false",
        "-1,false,true"
    })
    void completionReserveRespectsCeilingAndSchemaRepair(long limit, boolean repair, boolean obey)
            throws Exception {
        var think =
                AgentToolDefinition.from(
                        "fixture_loop",
                        FixtureLoopTool.class,
                        FixtureLoopTool.Args.class,
                        ToolCapability.LOOP_CONTROL);
        var finish =
                AgentToolDefinition.from(
                        "finish",
                        FixtureLoopTool.class,
                        FixtureLoopTool.Args.class,
                        ToolCapability.LOOP_CONTROL);
        ToolEngine engine = Mockito.mock(ToolEngine.class);
        Mockito.when(engine.getActiveTools(Mockito.any())).thenReturn(List.of(think, finish));
        Mockito.when(engine.resolveDefinition("fixture_loop")).thenReturn(think);
        Mockito.when(engine.resolveDefinition("finish")).thenReturn(finish);
        List<String> executed = new CopyOnWriteArrayList<>();
        Mockito.when(engine.execute(Mockito.any(), Mockito.any()))
                .thenAnswer(
                        invocation -> {
                            ToolCall call = invocation.getArgument(0);
                            if (call == null) throw new AssertionError("Missing call");
                            executed.add(call.toolName());
                            if (call.toolName().equals("finish"))
                                ToolCallContextHolder.finish("done");
                            return ToolResult.success(call.toolName(), call.callId(), "done");
                        });
        List<VetoRequest> requests = new CopyOnWriteArrayList<>();
        var service =
                serviceWith(
                        (request, modelSessionId) -> {
                            requests.add(request);
                            if (repair && limit != 4 && requests.size() == 1)
                                return new VetoResponse(null, null, null);
                            String tool =
                                    !obey
                                                    || (limit == 4 && requests.size() <= 3)
                                                    || (limit < 0 && requests.size() == 1)
                                            ? "fixture_loop"
                                            : "finish";
                            return new VetoResponse(
                                    null, List.of(new ToolCall(tool, Map.of())), null);
                        },
                        limit,
                        engine,
                        new HitlRegistry());
        String session = UUID.randomUUID().toString();
        var agent =
                service.getOrCreateAgent(
                        session,
                        UUID.randomUUID().toString(),
                        binding("System"),
                        List.of(),
                        UUID.randomUUID(),
                        "owner",
                        null,
                        0,
                        ToolResultPresentationMode.BASIC);
        Object owned = ReflectionTestUtils.getField(agent, "runner");
        if (!(owned instanceof AgentRunner runner)) throw new AssertionError("Missing runner");
        runner.setExecutionPolicy(
                new AgentExecutionPolicy(
                        new IsolatedAgent.Terminal(
                                "finish",
                                limit >= 4 ? 2 : 1,
                                new AgentProfile.Prompt(
                                        "reader-completion", new JsonValue.ObjectValue(Map.of()))),
                        () -> {}));
        try {
            agent.submit("Complete within the configured budget.");
            var result = agent.await(EPISODE_TIMEOUT);
            assertEquals(limit != 0 && obey, result.success());
            assertEquals(limit < 0 ? 2 : limit, requests.size());
            assertEquals(
                    limit == 4
                            ? obey
                                    ? List.of("fixture_loop", "fixture_loop", "finish")
                                    : List.of("fixture_loop", "fixture_loop")
                            : limit < 0
                                    ? List.of("fixture_loop", "finish")
                                    : limit != 0 && obey ? List.of("finish") : List.of(),
                    executed);
            if (limit > 0) {
                var last = requests.getLast();
                assertEquals(
                        1,
                        last.messages().stream()
                                .filter(
                                        message ->
                                                message.promptSources().stream()
                                                        .anyMatch(
                                                                span ->
                                                                        span.source()
                                                                                .equals(
                                                                                        "reader-completion.mdc")))
                                .count());
                assertEquals(
                        List.of("finish"), last.tools().stream().map(tool -> tool.name()).toList());
                assertTrue(
                        last.messages().getLast().content().contains("final allowed model call"));
                if (repair)
                    assertTrue(
                            last.messages().stream()
                                    .anyMatch(
                                            message ->
                                                    message.content()
                                                            .contains("schema violation")));
            }
            assertTrue(
                    agent.history().stream()
                            .noneMatch(
                                    turn ->
                                            turn.payload()
                                                    .toString()
                                                    .contains("[Runtime budget]")));
        } finally {
            service.remove(session);
        }
    }

    @Test
    void responseAndThoughtReferenceAcceptedUsageAfterSchemaRetry() throws Exception {
        var attempts = new AtomicInteger();
        var service =
                serviceWith(
                        (request, modelSessionId) -> {
                            LlmSystemUsage.set(100, 7);
                            int attempt = attempts.incrementAndGet();
                            if (attempt == 1) throw new ModelSchemaException("synthetic retry");
                            if (attempt == 2)
                                return new VetoResponse(
                                        null,
                                        List.of(
                                                new ToolCall(
                                                        "missing_tool", Map.of(), "usage-tool")),
                                        null);
                            return new VetoResponse("thinking", null, "answer");
                        });
        try {
            service.submitNow("usage-reference", "New request", binding("System"));
            var agent = requireAgent(service.agent("usage-reference"));
            assertTrue(agent.await(EPISODE_TIMEOUT).success());
            List<@Nullable String> ids = new ArrayList<>();
            assertTrue(
                    agent.history().stream()
                            .anyMatch(
                                    turn ->
                                            turn.type() == TurnType.AGENT_INIT
                                                    && turn.payload().get("prompt_source")
                                                            instanceof Map<?, ?> source
                                                    && "default-system-prompt"
                                                            .equals(source.get("id"))));
            for (TurnRecord turn : agent.history()) {
                for (var usage : turn.llmUsage()) {
                    ids.add(usage.modelCallId());
                }
            }
            assertEquals(3, ids.size());
            String firstId = Nullness.requireNonNull(ids.getFirst());
            assertNotEquals(firstId, ids.getLast());
            assertTrue(
                    agent.history().stream().noneMatch(turn -> turn.type() == TurnType.TOOL_CALL));
            assertTrue(
                    agent.history().stream()
                            .anyMatch(
                                    turn ->
                                            turn.type() == TurnType.EXECUTION_ERROR
                                                    && Boolean.TRUE.equals(
                                                            turn.payload().get("recoverable"))));
            var outputs =
                    agent.history().stream()
                            .filter(
                                    turn ->
                                            turn.type() == TurnType.ASSISTANT_THOUGHT
                                                    || turn.type() == TurnType.ASSISTANT_RESPONSE)
                            .toList();
            assertEquals(2, outputs.size());
            for (TurnRecord output : outputs)
                assertEquals(
                        Nullness.requireNonNull(ids.getLast()),
                        output.payload().get("model_call_id"));
        } finally {
            service.remove("usage-reference");
        }
    }

    @Test
    void ordinaryProviderFailureRetainsItsDiagnosticObservation() throws Exception {
        var service =
                serviceWith(
                        (request, modelSessionId) -> {
                            throw new LlmException("provider unavailable", false);
                        });
        try {
            service.submitNow("ordinary-provider-error", "New request", binding("System"));
            var agent = requireAgent(service.agent("ordinary-provider-error"));
            assertFalse(agent.await(EPISODE_TIMEOUT).success());
            assertTrue(
                    agent.history().stream()
                            .anyMatch(
                                    turn ->
                                            turn.type() == TurnType.TOOL_RESPONSE
                                                    && String.valueOf(turn.payload().get("content"))
                                                            .contains("provider unavailable")));
            assertTrue(
                    agent.history().stream()
                            .anyMatch(turn -> turn.type() == TurnType.EXECUTION_ERROR));
            assertFalse(
                    agent.history().stream()
                            .anyMatch(turn -> "CANCELLED".equals(turn.payload().get("outcome"))));
        } finally {
            service.remove("ordinary-provider-error");
        }
    }

    @Test
    void protectedUserInputIsCapturedBeforeHistoryAndProvider() throws Exception {
        List<VetoRequest> requests = new CopyOnWriteArrayList<>();
        AgentService service =
                serviceWith(
                        (request, modelSessionId) -> {
                            requests.add(request);
                            return new VetoResponse("Processed the safe context.", null, "Done.");
                        });
        String session = UUID.randomUUID().toString();
        String agentId = UUID.randomUUID().toString();
        try (var plugins = PluginTestSupport.manager()) {
            service.attachSessionPlugins(PluginTestSupport.sessionPlugins(plugins));
            service.attachEventManager(PluginTestSupport.eventManager(plugins));
            var agent =
                    service.getOrCreateAgent(
                            session,
                            agentId,
                            binding("You are a helpful assistant."),
                            List.of(),
                            UUID.randomUUID(),
                            "alice",
                            null,
                            0,
                            ToolResultPresentationMode.BASIC);
            var scope = new Scope.AgentScope("alice", session, agentId);
            agent.submit("Inspect password=synthetic-token");
            assertTrue(agent.await(EPISODE_TIMEOUT).success());
            var userTurn =
                    agent.history().stream()
                            .filter(turn -> turn.type() == TurnType.USER_PROMPT)
                            .findFirst()
                            .orElseThrow();
            if (!(userTurn.payload().get("content") instanceof String captured))
                throw new AssertionError("Expected captured user content");
            assertTrue(captured.contains("[SECRET_REF:s_"), captured);
            assertFalse(captured.contains("synthetic-token"));
            assertEquals(1, requests.size());
            assertTrue(
                    requests.getFirst().messages().stream()
                            .anyMatch(message -> message.content().contains(captured)));
            assertTrue(
                    requests.getFirst().messages().stream()
                            .noneMatch(message -> message.content().contains("synthetic-token")));
            assertTrue(
                    agent.history().stream()
                            .noneMatch(
                                    turn -> turn.payload().toString().contains("synthetic-token")));
            agent.submit("Repeat password=synthetic-token");
            assertTrue(agent.await(EPISODE_TIMEOUT).success());
            String verification =
                    PluginTestSupport.protect(
                            plugins,
                            BeforeTextCommitEvent.Phase.INPUT,
                            scope,
                            "verification",
                            "password=synthetic-token");
            String reference = extractReference(verification);
            assertTrue(
                    captured.contains(reference),
                    () ->
                            "Repeated capture should reuse the original reference: original="
                                    + extractReference(captured)
                                    + ", repeated="
                                    + reference);
            assertEquals(2, requests.size());
            assertEquals(
                    "synthetic-token",
                    PluginTestSupport.reveal(plugins, scope, reference).orElseThrow());
            agent.terminate();
            assertTrue(agent.awaitTermination(EPISODE_TIMEOUT));
            assertTrue(PluginTestSupport.reveal(plugins, scope, reference).isEmpty());
        } finally {
            service.remove(session);
        }
    }

    private static @NonNull String extractReference(@NonNull String text) {
        var matcher = java.util.regex.Pattern.compile("s_[a-f0-9]{32}").matcher(text);
        if (!matcher.find()) throw new AssertionError("Expected reference is missing");
        return matcher.group();
    }

    @Test
    void ordinaryReferenceValidationFailurePermitsProviderAndUserHistory() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<@NonNull VetoRequest> requests = new CopyOnWriteArrayList<>();
        UniformLLMCaller caller =
                (request, modelSessionId) -> {
                    calls.incrementAndGet();
                    requests.add(request);
                    return new VetoResponse("Safe.", null, "Done.");
                };
        AgentService service = serviceWith(caller);
        String session = UUID.randomUUID().toString();
        try (var plugins = PluginTestSupport.manager()) {
            service.attachSessionPlugins(PluginTestSupport.sessionPlugins(plugins));
            service.attachEventManager(PluginTestSupport.eventManager(plugins));
            Agent agent =
                    service.getOrCreateAgent(
                            session,
                            UUID.randomUUID().toString(),
                            binding("You are a helpful assistant."),
                            List.of(),
                            UUID.randomUUID(),
                            "alice",
                            null);
            String input = "password=synthetic-token [SECRET_REF:forged]";
            agent.submit(input);
            assertTrue(agent.await(EPISODE_TIMEOUT).success());
            assertEquals(1, calls.get());
            VetoRequest delivered = requests.getFirst();
            boolean originalInputDelivered = false;
            for (ChatMessage message : delivered.messages())
                if (message.content().contains(input)) originalInputDelivered = true;
            assertTrue(originalInputDelivered);
            boolean originalInputRecorded = false;
            boolean answerRecorded = false;
            for (TurnRecord turn : agent.history()) {
                if (turn.type() == TurnType.USER_PROMPT
                        && input.equals(turn.payload().get("content")))
                    originalInputRecorded = true;
                if (turn.payload().toString().contains("Done.")) answerRecorded = true;
            }
            assertTrue(originalInputRecorded);
            assertTrue(answerRecorded);
            agent.submit("Please continue with safe text.");
            assertTrue(agent.await(EPISODE_TIMEOUT).success());
            assertEquals(2, calls.get());
        } finally {
            service.remove(session);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dueNotificationRecoversOnlyCompletedHistoryAfterOwnerUnlocks(boolean completed)
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch called = new CountDownLatch(1);
        var runtime =
                serviceWith(
                        (request, modelSessionId) -> {
                            assertEquals("alice", UserContext.get());
                            assertTrue(
                                    request.messages().toString().contains("Earlier conversation"));
                            calls.incrementAndGet();
                            called.countDown();
                            return new VetoResponse(null, null, "Notification handled");
                        });
        KeysteadVault vault = Mockito.mock(KeysteadVault.class);
        runtime.attachExecutionVault(vault);
        var registry =
                (SessionAgentRegistry)
                        Nullness.requireNonNull(
                                ReflectionTestUtils.getField(runtime, "sessionAgents"));
        SessionRepository sessions = Mockito.mock(SessionRepository.class);
        AgentInstanceRepository agents = Mockito.mock(AgentInstanceRepository.class);
        AgentPatternRepository patterns = Mockito.mock(AgentPatternRepository.class);
        SessionHistoryLoader history = Mockito.mock(SessionHistoryLoader.class);
        ModelTierRegistry tiers = Mockito.mock(ModelTierRegistry.class);
        var session = new SessionEntity("alice", "duplicate-name");
        var identity =
                new AgentEntity(
                        session.getId(),
                        null,
                        AgentEntity.Role.PRIMARY,
                        "Primary",
                        "DEEPSEEK",
                        "model",
                        "key");
        session.setPrimaryAgentId(identity.getId());
        ReflectionTestUtils.setField(identity, "recoveryVersion", 1);
        Mockito.when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        Mockito.when(agents.findById(identity.getId())).thenReturn(Optional.of(identity));
        Mockito.when(history.load(session.getId(), identity.getId()))
                .thenReturn(
                        completed
                                ? List.of(
                                        TurnRecord.userPrompt(1, "Earlier conversation"),
                                        new TurnRecord(
                                                2,
                                                TurnType.ASSISTANT_RESPONSE,
                                                Map.of("content", "Done"),
                                                null))
                                : List.of(TurnRecord.userPrompt(1, "Earlier conversation")));
        Mockito.when(tiers.resolve("alice", ModelTier.TOP))
                .thenReturn(
                        new ModelBinding(ProviderType.DEEPSEEK, "model", "key", 0.7, 4096, null));
        var sessionService =
                new SessionService(sessions, agents, patterns, runtime, history, tiers);
        MonitorRepository repository = Mockito.mock(MonitorRepository.class);
        var groups = new GroupRegistry();
        var monitors =
                new MonitorService(
                        repository,
                        new ObjectMapper().findAndRegisterModules(),
                        groups(groups),
                        host(sessionService, registry, vault));
        SessionPlugins selected = Mockito.mock(SessionPlugins.class);
        Mockito.when(selected.workSource(Mockito.anyString())).thenReturn(work(monitors));
        runtime.attachSessionPlugins(selected);
        runtime.attachEventManager(Mockito.mock(EventManager.class));
        var due = Instant.now().plusSeconds(10);
        monitors.createTimer("alice", session.getId(), identity.getId(), "Review", due);
        try {
            Mockito.when(vault.isUnlocked()).thenReturn(true);
            ReflectionTestUtils.invokeMethod(monitors, "tickAt", due.plusSeconds(1));
            assertTrue(runtime.agent(session.getId()) == null);
            assertEquals(1, monitors.pending(identity.getId(), session.getId()).size());
            Mockito.when(vault.isUnlocked("alice")).thenReturn(true);
            ReflectionTestUtils.invokeMethod(monitors, "tickAt", due.plusSeconds(2));
            if (!completed) {
                assertNull(runtime.agent(session.getId()));
                assertEquals(0, calls.get());
                assertEquals(1, monitors.pending(identity.getId(), session.getId()).size());
                return;
            }
            var restored = requireAgent(runtime.agent(session.getId()));
            assertEquals(identity.getId(), restored.id());
            assertEquals(UUID.fromString(session.getId()), restored.sessionId());
            assertTrue(called.await(5, TimeUnit.SECONDS));
            assertTrue(restored.await(EPISODE_TIMEOUT).success());
            assertTrue(monitors.pending(identity.getId(), session.getId()).isEmpty());
            ReflectionTestUtils.invokeMethod(monitors, "tickAt", due.plusSeconds(3));
            assertEquals(1, calls.get());
        } finally {
            var restored = runtime.agent(session.getId());
            runtime.remove(session.getId());
            if (restored != null) assertTrue(restored.awaitTermination(Duration.ofSeconds(5)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVAL", "BREAKER", "QUESTION"})
    void recordedUnfinishedRequestBlocksNotificationsUntilExplicitInput() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        List<VetoRequest> requests = new CopyOnWriteArrayList<>();
        var service =
                serviceWith(
                        (request, modelSessionId) -> {
                            requests.add(request);
                            called.countDown();
                            return new VetoResponse(null, null, "Handled");
                        });
        UUID session = UUID.randomUUID();
        String id = UUID.randomUUID().toString();
        var agent =
                (VetoAgent)
                        service.getOrCreateAgent(
                                session.toString(),
                                id,
                                binding("System"),
                                List.of(TurnRecord.userPrompt(1, "Explain TCP")),
                                UUID.randomUUID(),
                                null,
                                "D:/IdeaProjects/veto/work/tmp/unfinished-group",
                                0,
                                ToolResultPresentationMode.BASIC);
        MonitorService monitor = Mockito.mock(MonitorService.class);
        agent.attachWorkSource(work(monitor));
        try {
            assertEquals(AgentState.WAITING, agent.state());
            agent.signalWork();
            assertFalse(called.await(150, TimeUnit.MILLISECONDS));
            Mockito.verify(monitor, Mockito.never())
                    .pending(Mockito.anyString(), Mockito.anyString());
            agent.submit("continue");
            assertTrue(agent.await(EPISODE_TIMEOUT).success());
            assertEquals(1, requests.size());
            assertTrue(agent.executionWaitReason() == null);
            assertTrue(requests.get(0).messages().toString().contains("Explain TCP"));
            assertTrue(requests.get(0).messages().toString().contains("[Runtime interruption]"));
            assertTrue(
                    agent.history().stream()
                            .anyMatch(turn -> "INTERRUPTED".equals(turn.payload().get("outcome"))));
        } finally {
            service.remove(session.toString());
            assertTrue(agent.awaitTermination(Duration.ofSeconds(5)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void recoveredAppendedNotificationRunsOnceAndPersistsActualEpisodeOutcome(boolean success)
            throws Exception {
        UUID session = UUID.randomUUID();
        String agentId = UUID.randomUUID().toString();
        var event =
                new MonitorRecord.Event("timer:due", "timer", "TIME_ONCE", "Review", Instant.now());
        var snapshot =
                new MonitorRecord(
                                "timer",
                                "owner",
                                session.toString(),
                                agentId,
                                "TIME_ONCE",
                                "Review",
                                null,
                                Instant.now(),
                                "COMPLETED",
                                Map.of("fired", "true"),
                                List.of(),
                                Instant.now(),
                                List.of(event))
                        .withActivation(event.id(), ActivationState.APPENDED);
        var mapper = new ObjectMapper().findAndRegisterModules();
        MonitorRepository repository = Mockito.mock(MonitorRepository.class);
        Mockito.when(repository.findAll())
                .thenReturn(
                        List.of(
                                new MonitorEntity(
                                        snapshot.id(), mapper.writeValueAsString(snapshot))));
        var monitor = new MonitorService(repository, mapper, groups(new GroupRegistry()), null);
        monitor.restore();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var service =
                serviceWith(
                        (request, modelSessionId) -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("Not released");
                            } catch (InterruptedException error) {
                                throw new IllegalStateException(error);
                            }
                            if (!success) throw new IllegalStateException("Provider unavailable");
                            return new VetoResponse(null, null, "Review finished");
                        });
        try {
            var agent =
                    (VetoAgent)
                            service.getOrCreateAgent(
                                    session.toString(),
                                    agentId,
                                    binding("System"),
                                    List.of(
                                            new TurnRecord(
                                                    1,
                                                    TurnType.RUNTIME_EVENT,
                                                    Map.of(
                                                            "eventId",
                                                            event.id(),
                                                            "content",
                                                            "Review"),
                                                    Instant.now())),
                                    UUID.randomUUID(),
                                    null,
                                    "D:/IdeaProjects/veto/work/tmp/unfinished-group",
                                    0,
                                    ToolResultPresentationMode.BASIC);
            agent.attachWorkSource(work(monitor));
            agent.signalWork();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(
                    ActivationState.RUNNING,
                    Nullness.requireNonNull(
                                    monitor.list("owner", session.toString())
                                            .getFirst()
                                            .activationStates()
                                            .get(event.id()))
                            .state());
            assertTrue(monitor.pending(agentId, session.toString()).isEmpty());
            release.countDown();
            assertEquals(success, agent.await(EPISODE_TIMEOUT).success());
            assertEquals(
                    success ? ActivationState.COMPLETED : ActivationState.FAILED,
                    Nullness.requireNonNull(
                                    monitor.list("owner", session.toString())
                                            .getFirst()
                                            .activationStates()
                                            .get(event.id()))
                            .state());
            assertEquals(1, calls.get());
            assertEquals(
                    1,
                    agent.history().stream()
                            .filter(t -> t.type() == TurnType.RUNTIME_EVENT)
                            .filter(t -> !t.payload().containsKey("restored_from_turn"))
                            .count());
            assertEquals(
                    1,
                    HistoryProjection.effective(agent.history()).stream()
                            .filter(t -> t.type() == TurnType.RUNTIME_EVENT)
                            .count());
        } finally {
            release.countDown();
            service.remove(session.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 2L})
    void restartedNotificationRestoresOriginalTaskAndRemainingBudget(long maxCalls)
            throws Exception {
        var repository = Mockito.mock(RequestContinuationRepository.class);
        Map<String, RequestContinuationEntity> durable = new ConcurrentHashMap<>();
        Mockito.when(repository.saveAndFlush(Mockito.any()))
                .thenAnswer(
                        invocation -> {
                            RequestContinuationEntity row = invocation.getArgument(0);
                            if (row == null) throw new AssertionError("Missing checkpoint");
                            durable.put(row.getId(), row);
                            return row;
                        });
        Mockito.when(repository.findById(Mockito.anyString()))
                .thenAnswer(
                        invocation -> Optional.ofNullable(durable.get(invocation.getArgument(0))));
        var store = new RequestContinuationStore(repository);
        List<VetoRequest> requests = new CopyOnWriteArrayList<>();
        UniformLLMCaller caller =
                (request, modelSessionId) -> {
                    assertFalse(
                            durable.isEmpty(), "Budget must be saved before provider execution");
                    requests.add(request);
                    return new VetoResponse(null, null, "done");
                };
        UUID session = UUID.randomUUID();
        var first = serviceWith(caller, maxCalls);
        first.attachContinuationStore(store);
        first.getOrCreateAgent(
                session.toString(),
                UUID.randomUUID().toString(),
                binding("System"),
                List.of(),
                UUID.randomUUID(),
                null,
                "D:/IdeaProjects/veto/work/tmp/unfinished-group",
                0,
                ToolResultPresentationMode.BASIC);
        first.submit(session.toString(), "Review apples", binding("System"), EPISODE_TIMEOUT);
        var original = requireAgent(first.agent(session.toString()));
        String requestId = requestIdentity(original);
        List<TurnRecord> history = original.history();
        String agentId = original.id();
        first.remove(session.toString());
        assertTrue(original.awaitTermination(Duration.ofSeconds(5)));
        // A checkpoint from an explicitly extended request must survive a new runtime whose
        // configured single-segment limit is smaller than that saved total allowance.
        if (maxCalls == 2) store.save(session, agentId, requestId, "Review apples", 3, 4L);
        var restarted = serviceWith(caller, maxCalls);
        restarted.attachContinuationStore(store);
        try {
            var agent =
                    (VetoAgent)
                            restarted.getOrCreateAgent(
                                    session.toString(),
                                    agentId,
                                    binding("System"),
                                    history,
                                    UUID.randomUUID(),
                                    null,
                                    "D:/IdeaProjects/veto/work/tmp/unfinished-group",
                                    0,
                                    ToolResultPresentationMode.BASIC);
            var event =
                    new MonitorRecord.Event(
                            "late",
                            "group",
                            "RESOURCE_EVENT",
                            "Apple review completed",
                            Instant.now(),
                            requestId,
                            "dispatch");
            var acknowledged = new CountDownLatch(1);
            MonitorService monitors = Mockito.mock(MonitorService.class);
            Mockito.when(monitors.pending(agentId, session.toString()))
                    .thenAnswer(
                            invocation ->
                                    acknowledged.getCount() == 0 ? List.of() : List.of(event));
            Mockito.doAnswer(
                            invocation -> {
                                acknowledged.countDown();
                                return null;
                            })
                    .when(monitors)
                    .acknowledge(agentId, event);
            agent.attachWorkSource(work(monitors));
            agent.signalWork();
            assertTrue(acknowledged.await(5, TimeUnit.SECONDS));
            assertEquals(maxCalls == 2, agent.await(EPISODE_TIMEOUT).success());
            assertEquals(maxCalls, requests.size());
            assertEquals(
                    maxCalls == 2 ? 4 : 1,
                    store.load(session, agentId, requestId).orElseThrow().consumedCalls());
            assertEquals(
                    Long.valueOf(maxCalls == 2 ? 4 : 1),
                    store.load(session, agentId, requestId).orElseThrow().grantedCalls());
            assertEquals(
                    "Review apples", store.load(session, agentId, requestId).orElseThrow().task());
            assertTrue(
                    agent.history().stream()
                            .filter(t -> t.type() == TurnType.RUNTIME_EVENT)
                            .anyMatch(
                                    t ->
                                            String.valueOf(t.payload().get("compiled_observation"))
                                                    .contains("Review apples")));
        } finally {
            restarted.remove(session.toString());
        }
    }

    @Test
    void failedBudgetReservationDoesNotCallProvider() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var service =
                serviceWith(
                        (request, modelSessionId) -> {
                            calls.incrementAndGet();
                            return new VetoResponse(null, null, "unexpected");
                        });
        RequestContinuationStore store = Mockito.mock(RequestContinuationStore.class);
        Mockito.doThrow(new IllegalStateException("Checkpoint unavailable"))
                .when(store)
                .save(
                        Mockito.any(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyLong(),
                        Mockito.anyLong());
        service.attachContinuationStore(store);
        TurnRecordRepository records = Mockito.mock(TurnRecordRepository.class);
        var logged = new ArrayList<String>();
        Mockito.when(records.save(Mockito.any()))
                .thenAnswer(
                        invocation -> {
                            TurnRecordEntity record = invocation.getArgument(0);
                            if (record == null) throw new IllegalStateException("Missing turn");
                            logged.add(record.getType());
                            return record;
                        });
        ReflectionTestUtils.setField(
                service, "turnLogService", new TurnLogService(records, new ObjectMapper()));
        try {
            assertFalse(
                    service.submit("checkpoint-failure", "Work", binding("System"), EPISODE_TIMEOUT)
                            .success());
            assertEquals(0, calls.get());
            assertTrue(logged.contains("EXECUTION_ERROR"));
            var failure =
                    requireAgent(service.agent("checkpoint-failure")).history().stream()
                            .filter(turn -> turn.type() == TurnType.EXECUTION_ERROR)
                            .findFirst()
                            .orElseThrow();
            assertTrue(failure.payload().get("content") instanceof String);
            assertTrue(failure.payload().get("requestId") instanceof String);
        } finally {
            service.remove("checkpoint-failure");
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 3L})
    void restoredTimerOccurrenceCannotAcquireAnotherBudget(long consumed) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var service =
                serviceWith(
                        (request, modelSessionId) -> {
                            calls.incrementAndGet();
                            return new VetoResponse(null, null, "unexpected");
                        },
                        1L);
        UUID session = UUID.randomUUID();
        String agentId = UUID.randomUUID().toString();
        RequestContinuationStore store = Mockito.mock(RequestContinuationStore.class);
        Mockito.when(store.load(session, agentId, "monitor:timer-event"))
                .thenReturn(
                        Optional.of(
                                new RequestContinuationStore.Checkpoint(
                                        "Original timer purpose",
                                        consumed,
                                        consumed == 1 ? null : consumed)));
        service.attachContinuationStore(store);
        try {
            var agent =
                    (VetoAgent)
                            service.getOrCreateAgent(
                                    session.toString(),
                                    agentId,
                                    binding("System"),
                                    List.of(),
                                    UUID.randomUUID(),
                                    null,
                                    "D:/IdeaProjects/veto/work/tmp/unfinished-group",
                                    0,
                                    ToolResultPresentationMode.BASIC);
            var event =
                    new MonitorRecord.Event(
                            "timer-event", "timer", "TIME_ONCE", "Timer fired", Instant.now());
            MonitorService monitors = Mockito.mock(MonitorService.class);
            var acknowledged = new CountDownLatch(1);
            Mockito.when(monitors.pending(agentId, session.toString()))
                    .thenAnswer(
                            invocation ->
                                    acknowledged.getCount() == 0 ? List.of() : List.of(event));
            Mockito.doAnswer(
                            invocation -> {
                                acknowledged.countDown();
                                return null;
                            })
                    .when(monitors)
                    .acknowledge(agentId, event);
            agent.attachWorkSource(work(monitors));
            agent.signalWork();
            assertTrue(acknowledged.await(5, TimeUnit.SECONDS));
            assertFalse(agent.await(EPISODE_TIMEOUT).success());
            assertEquals(0, calls.get());
            Mockito.verify(store, Mockito.never())
                    .save(
                            Mockito.any(),
                            Mockito.anyString(),
                            Mockito.anyString(),
                            Mockito.anyString(),
                            Mockito.anyLong(),
                            Mockito.anyLong());
        } finally {
            service.remove(session.toString());
        }
    }

    @Test
    void restoredHistoryPrecedesSafeConfigurationTransition() throws Exception {
        List<VetoRequest> requests = new CopyOnWriteArrayList<>();
        var service =
                serviceWith(
                        (request, modelSessionId) -> {
                            requests.add(request);
                            return new VetoResponse(null, null, "done");
                        });
        var plugins = Mockito.mock(SessionPlugins.class);
        Mockito.when(plugins.tools(Mockito.anyString(), Mockito.any()))
                .thenAnswer(call -> call.getArgument(1));
        var transition =
                new AgentConfiguration.Transition(
                        "configured",
                        "runtime-observation",
                        new JsonValue.ObjectValue(
                                Map.of(
                                        "content",
                                        new JsonValue.StringValue("configured"),
                                        "originatingTask",
                                        new JsonValue.StringValue("prior task"))));
        Mockito.when(
                        plugins.configure(
                                Mockito.anyString(),
                                Mockito.anyString(),
                                Mockito.anyString(),
                                Mockito.isNull(),
                                Mockito.any(),
                                Mockito.any(),
                                Mockito.anyString()))
                .thenAnswer(call -> new AgentConfiguration.Intent(call.getArgument(4), transition));
        service.attachSessionPlugins(plugins);
        service.attachEventManager(Mockito.mock(EventManager.class));
        service.setModelTierRegistry(Mockito.mock(ModelTierRegistry.class));
        String session = UUID.randomUUID().toString();
        try {
            var agent =
                    service.getOrCreateAgent(
                            session,
                            UUID.randomUUID().toString(),
                            binding("System"),
                            List.of(TurnRecord.userPrompt(1, "prior persisted request")),
                            UUID.randomUUID(),
                            "owner",
                            "D:/IdeaProjects/veto/work/tmp/runner-state-storage",
                            0,
                            ToolResultPresentationMode.BASIC);
            agent.submit("New request");
            assertTrue(agent.await(EPISODE_TIMEOUT).success());
            assertTrue(
                    agent.history().stream()
                            .anyMatch(
                                    turn ->
                                            turn.payload()
                                                    .toString()
                                                    .contains("prior persisted request")));
            assertEquals(
                    1,
                    agent.history().stream()
                            .filter(
                                    turn ->
                                            turn.payload()
                                                    .toString()
                                                    .contains("[Runtime observation]"))
                            .count());
            assertTrue(
                    requests.stream()
                            .anyMatch(
                                    request ->
                                            request.messages()
                                                    .toString()
                                                    .contains("prior persisted request")));
        } finally {
            service.remove(session);
        }
    }

    @Test
    void citationCorrectionHasAnOpportunityAfterSchemaCorrections() throws Exception {
        var calls = new AtomicInteger();
        var service =
                serviceWithCitationPolicy(
                        "citation-after-schema",
                        (request, modelSessionId) -> {
                            int call = calls.incrementAndGet();
                            if (call <= 2) throw new ModelSchemaException("Invalid JSON shape");
                            return new VetoResponse(
                                    null,
                                    null,
                                    "[Meeting](cite:meeting)",
                                    List.of(
                                            new VetoResponse.Citation(
                                                    "meeting",
                                                    List.of(
                                                            new VetoResponse.Source(
                                                                    call == 3 ? 99 : 0,
                                                                    "14:30")))));
                        });
        assertTrue(
                service.submit(
                                "citation-after-schema",
                                "Meeting at 14:30",
                                binding("System"),
                                EPISODE_TIMEOUT)
                        .success());
        assertEquals(4, calls.get());
    }

    @Test
    void malformedCorrectionsPreserveTheCandidateAndItsOriginalSourceBinding() throws Exception {
        var calls = new AtomicInteger();
        var service =
                serviceWithCitationPolicy(
                        "citation-candidate",
                        (request, modelSessionId) -> {
                            if (calls.incrementAndGet() > 1)
                                throw new ModelSchemaException("Invalid correction");
                            return new VetoResponse(
                                    null,
                                    null,
                                    "[Meeting](cite:meeting)",
                                    List.of(
                                            new VetoResponse.Citation(
                                                    "meeting",
                                                    List.of(
                                                            new VetoResponse.Source(
                                                                    99, "14:30")))));
                        });
        var result =
                service.submit(
                        "citation-candidate",
                        "Meeting at 14:30",
                        binding("System"),
                        EPISODE_TIMEOUT);
        assertTrue(result.success());
        assertEquals(4, calls.get());
        var answer =
                requireAgent(service.agent("citation-candidate")).history().stream()
                        .filter(turn -> turn.type() == TurnType.ASSISTANT_RESPONSE)
                        .findFirst()
                        .orElseThrow();
        var saved = new ObjectMapper().valueToTree(answer.payload()).path("citation_context");
        assertEquals(1, saved.path("messageCount").asInt());
        assertEquals("not_found", saved.path("checks").get(0).path("status").asText());
    }

    @Test
    void exhaustedCitationCorrectionPreservesAnswerAndUnresolvedReference() throws Exception {
        var calls = new AtomicInteger();
        var service =
                serviceWithCitationPolicy(
                        "citation-unresolved",
                        (request, modelSessionId) -> {
                            calls.incrementAndGet();
                            return new VetoResponse(
                                    null,
                                    null,
                                    "The meeting is [at 14:30](cite:meeting).",
                                    List.of(
                                            new VetoResponse.Citation(
                                                    "meeting",
                                                    List.of(
                                                            new VetoResponse.Source(
                                                                    99, "14:30")))));
                        });
        var result =
                service.submit(
                        "citation-unresolved",
                        "Meeting at 14:30",
                        binding("System"),
                        EPISODE_TIMEOUT);
        assertTrue(result.success());
        assertEquals(3, calls.get());
        assertTrue(result.message().contains("14:30"));
        var answers =
                requireAgent(service.agent("citation-unresolved")).history().stream()
                        .filter(turn -> turn.type() == TurnType.ASSISTANT_RESPONSE)
                        .toList();
        assertEquals(1, answers.size());
        var saved =
                new ObjectMapper()
                        .valueToTree(answers.getFirst().payload())
                        .path("citation_context");
        assertEquals("not_found", saved.path("checks").get(0).path("status").asText());
        assertEquals(
                99,
                saved.path("checks").get(0).path("references").get(0).path("messageIndex").asInt());
        assertTrue(saved.path("checks").get(0).path("matches").isEmpty());
    }

    @Test
    void invalidCitationIsCorrectedAgainstTheActualRequestBeforePublishing() throws Exception {
        var calls = new AtomicInteger();
        var service =
                serviceWithCitationPolicy(
                        "citation-correction",
                        (request, modelSessionId) -> {
                            boolean first = calls.incrementAndGet() == 1;
                            return new VetoResponse(
                                    null,
                                    null,
                                    "[Meeting](cite:meeting)",
                                    List.of(
                                            new VetoResponse.Citation(
                                                    "meeting",
                                                    List.of(
                                                            new VetoResponse.Source(
                                                                    0,
                                                                    first ? "15:30" : "14:30")))));
                        });
        assertTrue(
                service.submit(
                                "citation-correction",
                                "Meeting at 14:30",
                                binding("System"),
                                EPISODE_TIMEOUT)
                        .success());
        assertEquals(2, calls.get());
        var answers =
                requireAgent(service.agent("citation-correction")).history().stream()
                        .filter(turn -> turn.type() == TurnType.ASSISTANT_RESPONSE)
                        .toList();
        assertEquals(1, answers.size());
        var saved =
                new ObjectMapper()
                        .valueToTree(answers.getFirst().payload())
                        .path("citation_context");
        assertEquals("matched", saved.path("checks").get(0).path("status").asText());
    }

    @Test
    void activeCitationBindsToSuccessfulRetryAndDoesNotLeakIntoLaterAnswers() throws Exception {
        var calls = new AtomicInteger();
        var mapper = new ObjectMapper();
        var service =
                serviceWithCitationPolicy(
                        "citation-retry",
                        (request, modelSessionId) -> {
                            if (calls.incrementAndGet() == 1)
                                throw new ModelSchemaException("try again");
                            if (calls.get() == 3)
                                return new VetoResponse(null, null, "No citation");
                            return new VetoResponse(
                                    null,
                                    null,
                                    "[Meeting](cite:meeting)",
                                    List.of(
                                            new VetoResponse.Citation(
                                                    "meeting",
                                                    List.of(new VetoResponse.Source(0, "14:30")))));
                        });
        assertTrue(
                service.submit(
                                "citation-retry",
                                "Meeting at 14:30",
                                binding("System"),
                                EPISODE_TIMEOUT)
                        .success());
        var agent = requireAgent(service.agent("citation-retry"));
        var answer =
                agent.history().stream()
                        .filter(turn -> turn.type() == TurnType.ASSISTANT_RESPONSE)
                        .findFirst()
                        .orElseThrow();
        var saved = mapper.valueToTree(answer.payload()).path("citation_context");
        assertEquals(2, saved.path("messageCount").asInt());
        assertEquals("matched", saved.path("checks").get(0).path("status").asText());
        int sourceTurn = saved.path("checks").get(0).path("matches").get(0).path("turn").asInt();
        assertTrue(
                agent.history().stream()
                        .anyMatch(
                                turn ->
                                        turn.turnNumber() == sourceTurn
                                                && turn.type() == TurnType.USER_PROMPT));
        assertTrue(
                service.submit("citation-retry", "Continue", binding("System"), EPISODE_TIMEOUT)
                        .success());
        var latest =
                agent.history().stream()
                        .filter(turn -> turn.type() == TurnType.ASSISTANT_RESPONSE)
                        .toList()
                        .getLast();
        assertFalse(latest.payload().containsKey("citation_context"));
    }

    @Test
    void linkageFailureCompletesTheEpisode() throws Exception {
        var service =
                serviceWith(
                        (request, modelSessionId) -> {
                            throw new NoClassDefFoundError("ToolErrors");
                        });
        var result =
                service.submit("linkage-failure", "Answer", binding("System"), EPISODE_TIMEOUT);
        assertFalse(result.success());
        assertTrue(result.message().contains("ToolErrors"));
    }

    @Test
    void configurationChangeAppliesAfterTheCurrentExchangeIncludingRepair() throws Exception {
        var active = new AtomicReference<VetoAgent>();
        List<VetoRequest> seen = new CopyOnWriteArrayList<>();
        var service =
                serviceWith(
                        (request, modelSessionId) -> {
                            seen.add(request);
                            if (seen.size() == 2) {
                                var agent = active.get();
                                if (agent == null) throw new AssertionError("Missing agent");
                                agent.bind(
                                        new LlmBinding(
                                                ProviderType.DEEPSEEK,
                                                "changed-model",
                                                "stub-key",
                                                LlmOptions.defaults(),
                                                null));
                                return new VetoResponse(null, null, null);
                            }
                            return new VetoResponse(null, null, "done");
                        });
        try {
            service.submit(
                    "model-snapshot", "Initialize", binding("Original system"), EPISODE_TIMEOUT);
            var agent = requireAgent(service.agent("model-snapshot"));
            active.set(agent);
            assertTrue(agent.submitRequest("Repair once").await(EPISODE_TIMEOUT).success());
            assertEquals(seen.get(1).systemPrompt(), seen.get(2).systemPrompt());
            assertTrue(
                    agent.submitRequest("Use updated configuration")
                            .await(EPISODE_TIMEOUT)
                            .success());
            assertNotEquals(seen.get(2).modelName(), seen.get(3).modelName());
        } finally {
            service.remove("model-snapshot");
        }
    }

    /** Only citation-policy scenarios select the builtin response contribution. */
    private static @NonNull AgentService serviceWithCitationPolicy(
            @NonNull String agentKey, @NonNull UniformLLMCaller caller) {
        var service = serviceWith(caller);
        SessionPlugins selected = Mockito.mock(SessionPlugins.class);
        Mockito.when(selected.responsePolicies(Mockito.anyString()))
                .thenAnswer(call -> List.of(new CitationResponsePolicy().open()));
        Mockito.when(selected.tools(Mockito.anyString(), Mockito.any()))
                .thenAnswer(call -> call.getArgument(1));
        // configure defaults to null: this fixture selects a response policy, not an agent profile.
        service.attachSessionPlugins(selected);
        service.attachEventManager(Mockito.mock(EventManager.class));
        service.getOrCreateAgent(
                agentKey,
                null,
                binding("System"),
                List.of(),
                UUID.randomUUID(),
                "citation-owner",
                null);
        return service;
    }

    @Test
    void repeatedSchemaFailuresRecoverInTheSameSession() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var service =
                serviceWith(
                        (request, modelSessionId) -> {
                            if (calls.incrementAndGet() <= 4)
                                throw new ModelSchemaException("Malformed JSON");
                            assertTrue(
                                    request.messages()
                                            .getLast()
                                            .content()
                                            .contains("Malformed JSON"));
                            return new VetoResponse(null, null, "Recovered");
                        });
        var result =
                service.submit("schema-recovery", "Answer", binding("System"), EPISODE_TIMEOUT);
        assertTrue(result.success());
        assertEquals(5, calls.get());
        var errors =
                requireAgent(service.agent("schema-recovery")).history().stream()
                        .filter(turn -> turn.type() == TurnType.EXECUTION_ERROR)
                        .toList();
        assertEquals(4, errors.size());
        assertTrue(
                errors.stream()
                        .allMatch(turn -> Boolean.TRUE.equals(turn.payload().get("recoverable"))));
        service.remove("schema-recovery");
    }

    @Test
    void providerSchemaFailureUsesTheSameEphemeralRetryPath() throws Exception {
        List<VetoRequest> requests = new CopyOnWriteArrayList<>();
        UniformLLMCaller caller =
                (request, modelSessionId) -> {
                    requests.add(request);
                    if (requests.size() == 1)
                        throw new ModelSchemaException("Malformed guide JSON");
                    assertTrue(
                            request.messages()
                                    .get(request.messages().size() - 1)
                                    .content()
                                    .contains("Malformed guide JSON"));
                    return new VetoResponse(null, null, "Recovered");
                };
        var service = serviceWith(caller);
        var result =
                service.submit(
                        "provider-schema-retry", "Answer", binding("System"), EPISODE_TIMEOUT);
        assertTrue(result.success(), result.message());
        assertEquals("Recovered", result.message());
        assertEquals(2, requests.size());
        var agent = requireAgent(service.agent("provider-schema-retry"));
        assertTrue(
                agent.history().stream()
                        .noneMatch(
                                turn ->
                                        turn.type() != TurnType.EXECUTION_ERROR
                                                && turn.payload()
                                                        .toString()
                                                        .contains("Malformed guide JSON")),
                "provider formatting rejection must remain ephemeral");
    }

    @Test
    void schemaViolationInjectsEphemeralRejectionMessageThenRetries() throws Exception {
        // A capturing caller: the first call returns a schema-violating response (no message,
        // calls, or guide
        // so ResponseEnforcer throws ModelSchemaException); the retry returns a valid stopping
        // response (no tool calls).
        List<VetoRequest> seenRequests = new CopyOnWriteArrayList<>();
        UniformLLMCaller caller =
                (request, modelSessionId) -> {
                    seenRequests.add(request);
                    if (seenRequests.size() == 1) {
                        return new VetoResponse(null, null, null);
                    }
                    return new VetoResponse("I'll answer directly.", null, "The answer is 4.");
                };

        AgentService service = serviceWith(caller);
        AgentResult result =
                service.submit(
                        "schema-retry",
                        "What is 2 + 2?",
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT);

        assertTrue(result.success(), "episode should finish after the schema retry");
        assertEquals(2, seenRequests.size(), "the caller is invoked once per attempt");

        // The retry request carries exactly one injected user-role rejection message.
        VetoRequest original = seenRequests.get(0);
        VetoRequest retried = seenRequests.get(1);
        assertEquals(
                original.messages().size() + 1,
                retried.messages().size(),
                "exactly one rejection message is appended for the retry");
        ChatMessage injected = retried.messages().get(retried.messages().size() - 1);
        assertEquals("user", injected.role(), "the rejection is a user-role turn");
        String rejection = injected.content();
        assertTrue(rejection.contains("schema violation"), "states the violation");
        assertTrue(rejection.contains("message"), "echoes the violation detail");
        assertTrue(rejection.contains("Expected:"), "carries the expected-description guidance");
        assertTrue(
                rejection.contains("Tool execution is disabled for this invocation"),
                "correction preserves the native channel");
        assertFalse(rejection.contains("VetoResponse"));

        // The rejection is ephemeral: it must not be recorded in turn history.
        VetoAgent agent = requireAgent(service.agent("schema-retry"));
        long userPromptTurns =
                agent.history().stream().filter(t -> t.type() == TurnType.USER_PROMPT).count();
        assertEquals(1, userPromptTurns, "only the original user prompt is recorded");
        for (TurnRecord turn : agent.history()) {
            assertFalse(
                    String.valueOf(turn.payload()).contains("schema violation"),
                    "rejection message leaked into history: " + turn);
        }
    }

    /**
     * A stopping turn (no tool calls, no actions) with no message triggers the message-required
     * rule; the rejection guidance must describe that requirement.
     */
    @Test
    void stoppingTurnWithoutMessageMapsToMessageDescription() throws Exception {
        List<VetoRequest> seenRequests = new CopyOnWriteArrayList<>();
        UniformLLMCaller caller =
                (request, modelSessionId) -> {
                    seenRequests.add(request);
                    if (seenRequests.size() == 1) {
                        // thought present + stopping (no calls) + message missing → Rule 3 throws
                        // "message required (thought OFF or stopping)".
                        return new VetoResponse("thinking...", null, null);
                    }
                    return new VetoResponse("I'll answer directly.", null, "The answer is 4.");
                };

        AgentService service = serviceWith(caller);
        AgentResult result =
                service.submit(
                        "message-required-retry",
                        "What is 2 + 2?",
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT);

        assertTrue(result.success(), "episode should finish after the schema retry");
        VetoRequest retried = seenRequests.get(1);
        String rejection = retried.messages().get(retried.messages().size() - 1).content();
        assertTrue(
                rejection.contains("message required"),
                "message-required violation maps to the message-required guidance: " + rejection);
        assertFalse(
                rejection.contains("thought field must not be present"),
                "must not mis-map to the thought-OFF guidance: " + rejection);
    }

    /**
     * A response that carries both a thought and a message must deliver the thought to the
     * thoughtSink and the message to the messageSink, with the thought arriving first (the loop
     * records + emits the thought before the message).
     */
    @Test
    void thoughtStreamsToThoughtSinkBeforeMessage() throws Exception {
        UniformLLMCaller caller =
                (request, modelSessionId) ->
                        new VetoResponse("I should answer directly.", null, "The answer is 4.");

        AgentService service = serviceWith(caller);
        List<String> thoughts = new CopyOnWriteArrayList<>();
        List<String> messages = new CopyOnWriteArrayList<>();
        AtomicInteger order = new AtomicInteger();
        List<String> sequence = new CopyOnWriteArrayList<>();

        AgentResult result =
                service.submit(
                        "thought-stream",
                        "What is 2 + 2?",
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT,
                        m -> {
                            messages.add(m);
                            sequence.add("message:" + order.incrementAndGet());
                        },
                        null,
                        t -> {
                            thoughts.add(t);
                            sequence.add("thought:" + order.incrementAndGet());
                        });

        assertTrue(result.success(), "episode should finish cleanly");
        assertEquals(1, thoughts.size(), "the thought is delivered to the thoughtSink once");
        assertEquals(
                "I should answer directly.",
                thoughts.get(0),
                "the thought text is forwarded verbatim");
        assertEquals(1, messages.size(), "the message is delivered to the messageSink once");
        assertEquals("The answer is 4.", messages.get(0), "the message text is forwarded verbatim");
        assertEquals(
                "thought:1",
                sequence.get(0),
                "the thought must stream BEFORE the message so the terminal renders reasoning"
                        + " ahead of the answer");
        assertEquals("message:2", sequence.get(1));
    }
}
