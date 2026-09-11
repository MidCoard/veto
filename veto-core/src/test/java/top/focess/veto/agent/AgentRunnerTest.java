package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.capability.UserInteractionCapabilityImpl;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.identity.RoleToolFilter;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.intercept.ApprovalDecision;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.intercept.VetoOption;
import top.focess.veto.agent.intercept.VetoScenario;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.AgentTool;
import top.focess.veto.agent.tool.NativeToolDefinition;
import top.focess.veto.agent.tool.RemoteToolDefinition;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.agent.tool.ToolEngineImpl;
import top.focess.veto.agent.tool.ToolResult;
import top.focess.veto.agent.tool.builtin.AskUserTool;
import top.focess.veto.agent.tool.builtin.RunTaskTool;
import top.focess.veto.agent.tool.builtin.UserQuestionRegistry;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.group.Blackboard;
import top.focess.veto.group.DagNode;
import top.focess.veto.group.ExecutionDag;
import top.focess.veto.group.Group;
import top.focess.veto.group.GroupHistoryEntity;
import top.focess.veto.group.GroupHistoryRepository;
import top.focess.veto.group.GroupHistoryStore;
import top.focess.veto.group.GroupOrchestrator;
import top.focess.veto.group.GroupRecoveryService;
import top.focess.veto.group.GroupRegistry;
import top.focess.veto.group.GroupSpawner;
import top.focess.veto.group.LeaderBinding;
import top.focess.veto.group.MateBreakerRegistry;
import top.focess.veto.group.SkillsetProperties;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.llm.exceptions.LlmException;
import top.focess.veto.llm.exceptions.ModelSchemaException;
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
import top.focess.veto.monitor.MonitorAgentActivator;
import top.focess.veto.monitor.MonitorEntity;
import top.focess.veto.monitor.MonitorRecord;
import top.focess.veto.monitor.MonitorRecord.ActivationState;
import top.focess.veto.monitor.MonitorRepository;
import top.focess.veto.monitor.MonitorService;
import top.focess.veto.monitor.RequestContinuationEntity;
import top.focess.veto.monitor.RequestContinuationRepository;
import top.focess.veto.monitor.RequestContinuationStore;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.sandbox.SandboxManager;
import top.focess.veto.sandbox.TestSandboxFactory;
import top.focess.veto.session.SessionHistoryLoader;
import top.focess.veto.session.SessionService;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.vault.SecretCandidateStore;
import top.focess.veto.vault.UserContext;

/**
 * Targets {@link AgentRunner}'s schema-violation retry path in isolation from the broader
 * end-to-end flows covered by {@code AgentEndToEndTest}. On a {@link ModelSchemaException} (thrown
 * by {@code ResponseEnforcer}) the runner must inject an ephemeral user-role rejection message into
 * the retry request — guiding the model to regenerate without persisting the rejection into turn
 * history.
 */
class AgentRunnerTest {
    @Test
    void ordinaryProviderFailureRetainsItsDiagnosticObservation() throws Exception {
        var service =
                serviceWith(
                        request -> {
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void protectedUserInputIsCapturedBeforeHistoryAndProvider(boolean guidedEnabled)
            throws Exception {
        List<VetoRequest> requests = new CopyOnWriteArrayList<>();
        AgentService service =
                serviceWith(
                        request -> {
                            requests.add(request);
                            return new VetoResponse(
                                    "Processed the safe context.", null, "Done.", null);
                        });
        var candidates = new SecretCandidateStore();
        service.attachSecretCandidates(candidates);
        String session = UUID.randomUUID().toString();
        String agentId = UUID.randomUUID().toString();
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
                        ToolResultPresentationMode.BASIC,
                        guidedEnabled);
        var scope = new SecretCandidateStore.Scope("alice", session, agentId);
        try {
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
            String reference =
                    candidates
                            .capture(scope, "verification", "password=synthetic-token")
                            .candidates()
                            .getFirst()
                            .reference();
            assertTrue(captured.contains(reference));
            assertEquals(2, requests.size());
            agent.terminate();
            assertEquals(
                    SecretCandidateStore.State.DISCARDED,
                    candidates.describe(scope, reference).orElseThrow().state());
        } finally {
            service.remove(session);
        }
    }

    @Test
    void invalidProtectedReferenceStopsBeforeProviderAndUserHistory() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AgentService service =
                serviceWith(
                        request -> {
                            calls.incrementAndGet();
                            return new VetoResponse("Safe.", null, "Done.", null);
                        });
        service.attachSecretCandidates(new SecretCandidateStore());
        String session = UUID.randomUUID().toString();
        var agent =
                service.getOrCreateAgent(
                        session,
                        UUID.randomUUID().toString(),
                        binding("You are a helpful assistant."),
                        List.of(),
                        UUID.randomUUID(),
                        "alice",
                        null);
        try {
            var previousResult = agent.result();
            var rejected =
                    assertThrows(
                            ToolDocs.nonNullClass(ProtectedInputException.class),
                            () -> agent.submit("password=synthetic-token [SECRET_REF:forged]"));
            assertFalse(String.valueOf(rejected.getMessage()).contains("synthetic-token"));
            assertSame(previousResult, agent.result());
            if (!(agent instanceof VetoAgent live)) throw new AssertionError("Expected live agent");
            assertFalse(live.hasPendingWork());
            assertEquals(0, calls.get());
            assertTrue(
                    agent.history().stream()
                            .noneMatch(turn -> turn.type() == TurnType.USER_PROMPT));
            assertTrue(
                    agent.history().stream()
                            .noneMatch(
                                    turn -> turn.payload().toString().contains("synthetic-token")));
            agent.submit("Please continue with safe text.");
            assertTrue(agent.await(EPISODE_TIMEOUT).success());
            assertEquals(1, calls.get());
        } finally {
            service.remove(session);
        }
    }

    @Test
    void dueNotificationRecreatesOriginalRunnerOnlyAfterItsOwnerUnlocks() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch called = new CountDownLatch(1);
        var runtime =
                serviceWith(
                        request -> {
                            assertEquals("alice", UserContext.get());
                            assertTrue(
                                    request.messages().toString().contains("Earlier conversation"));
                            calls.incrementAndGet();
                            called.countDown();
                            return new VetoResponse(null, null, "Notification handled", null);
                        });
        @NonNull KeysteadVault vault = Mockito.mock();
        runtime.attachMonitorVault(vault);
        var registry =
                (SessionAgentRegistry)
                        Nullness.requireNonNull(
                                ReflectionTestUtils.getField(runtime, "sessionAgents"));
        @NonNull SessionRepository sessions = Mockito.mock();
        @NonNull AgentInstanceRepository agents = Mockito.mock();
        @NonNull AgentPatternRepository patterns = Mockito.mock();
        @NonNull SessionHistoryLoader history = Mockito.mock();
        @NonNull ModelTierRegistry tiers = Mockito.mock();
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
        ReflectionTestUtils.setField(identity, "monitorRecoveryVersion", 1);
        Mockito.when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        Mockito.when(agents.findById(identity.getId())).thenReturn(Optional.of(identity));
        Mockito.when(history.load(session.getId(), identity.getId()))
                .thenReturn(List.of(TurnRecord.userPrompt(1, "Earlier conversation")));
        Mockito.when(tiers.resolve("alice", ModelTier.TOP))
                .thenReturn(
                        new ModelBinding(ProviderType.DEEPSEEK, "model", "key", 0.7, 4096, null));
        var sessionService =
                new SessionService(sessions, agents, patterns, runtime, history, tiers);
        @NonNull MonitorRepository repository = Mockito.mock();
        var groups = new GroupRegistry();
        var monitors =
                new MonitorService(
                        repository, new ObjectMapper().findAndRegisterModules(), groups, registry);
        registry.attachMonitor(monitors);
        monitors.attachActivator(
                new MonitorAgentActivator(sessionService, registry, vault, groups));
        var due = Instant.now().plusSeconds(10);
        monitors.createTimer("alice", session.getId(), identity.getId(), "Review", due);
        try {
            Mockito.when(vault.isUnlocked()).thenReturn(true);
            ReflectionTestUtils.invokeMethod(monitors, "tickAt", due.plusSeconds(1));
            assertTrue(runtime.agent(session.getId()) == null);
            assertEquals(1, monitors.pending(identity.getId(), session.getId()).size());
            Mockito.when(vault.isUnlocked("alice")).thenReturn(true);
            ReflectionTestUtils.invokeMethod(monitors, "tickAt", due.plusSeconds(2));
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

    private static @NonNull ToolEngine questionEngine(@NonNull UserQuestionRegistry questions) {
        @NonNull ApplicationContext spring = Mockito.mock();
        var tool = new AskUserTool(new UserInteractionCapabilityImpl(questions));
        Mockito.when(spring.getBeansOfType(AgentTool.class))
                .thenReturn(Map.of("askUserTool", tool));
        var engine = new ToolEngineImpl(new ObjectMapper(), List.of(), spring);
        ReflectionTestUtils.invokeMethod(engine, "init");
        return engine;
    }

    private static @NonNull ToolCall questionCall() {
        return new ToolCall(
                "ask_user",
                Map.of(
                        "questions",
                        List.of(
                                Map.of(
                                        "header",
                                        "Format",
                                        "id",
                                        "format",
                                        "question",
                                        "Which format?",
                                        "options",
                                        List.of(
                                                Map.of(
                                                        "label",
                                                        "Text (Recommended)",
                                                        "description",
                                                        "Simple"),
                                                Map.of(
                                                        "label",
                                                        "Markdown",
                                                        "description",
                                                        "Formatted"))))),
                "question-call");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ANSWER", "CANCEL", "INTERRUPT", "HISTORY_FAIL"})
    void actualQuestionWaitPersistsUntilAnswerOrCancellation(@NonNull String action)
            throws Exception {
        var questions = new UserQuestionRegistry();
        AtomicInteger calls = new AtomicInteger();
        var service =
                serviceWith(
                        request ->
                                calls.getAndIncrement() == 0
                                        ? new VetoResponse(
                                                "Need a format",
                                                List.of(questionCall()),
                                                null,
                                                null)
                                        : new VetoResponse(null, null, "Handled", null),
                        5,
                        questionEngine(questions),
                        new HitlRegistry());
        @NonNull AgentWaitStore waits = Mockito.mock();
        AtomicReference<AgentWaitStore.@Nullable Wait> saved = new AtomicReference<>();
        Mockito.when(waits.load(Mockito.any(), Mockito.anyString())).thenReturn(Optional.empty());
        Mockito.doAnswer(
                        invocation -> {
                            saved.set(invocation.getArgument(2));
                            return null;
                        })
                .when(waits)
                .save(Mockito.any(), Mockito.anyString(), Mockito.any());
        service.attachWaitStore(waits);
        if (action.equals("HISTORY_FAIL")) {
            @NonNull TurnLogService turns = Mockito.mock();
            Mockito.doThrow(new IllegalStateException("Answer log unavailable"))
                    .when(turns)
                    .logRequired(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString());
            ReflectionTestUtils.setField(service, "turnLogService", turns);
        }
        service.submitNow("question-wait", "Ask for a format", binding("System"));
        var agent = requireAgent(service.agent("question-wait"));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (questions.pendingFor(agent.id()).isEmpty() && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertEquals(1, questions.pendingFor(agent.id()).size());
            assertEquals(
                    AgentWaitStore.Reason.QUESTION, Nullness.requireNonNull(saved.get()).reason());
            assertFalse(agent.result().isDone());
            if (action.equals("INTERRUPT")) {
                assertTrue(agent.cancelTask(agent.result(), Duration.ofSeconds(5)));
                assertFalse(agent.await(EPISODE_TIMEOUT).success());
                assertEquals(
                        AgentWaitStore.Reason.QUESTION,
                        Nullness.requireNonNull(saved.get()).reason());
                assertEquals(1, calls.get());
            } else {
                if (!action.equals("CANCEL"))
                    assertTrue(
                            questions.answer(
                                    agent.id(), "question-call", Map.of("format", "Markdown")));
                else assertTrue(questions.cancel(agent.id(), "question-call"));
                if (action.equals("HISTORY_FAIL")) {
                    assertFalse(agent.await(EPISODE_TIMEOUT).success());
                    assertEquals(
                            AgentWaitStore.Reason.QUESTION,
                            Nullness.requireNonNull(saved.get()).reason());
                    assertEquals(1, calls.get());
                } else {
                    assertTrue(agent.await(EPISODE_TIMEOUT).success());
                    assertTrue(saved.get() == null);
                    assertEquals(2, calls.get());
                }
            }
            assertTrue(questions.pendingFor(agent.id()).isEmpty());
        } finally {
            service.remove("question-wait");
            assertTrue(agent.awaitTermination(Duration.ofSeconds(5)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void taskCancellationClearsResolvedApprovalUnlessCheckpointFails(boolean failSave)
            throws Exception {
        @NonNull ToolEngine engine = Mockito.mock();
        var definition =
                new RemoteToolDefinition(
                        "external_test",
                        "Test external action",
                        "test",
                        new ObjectMapper().createObjectNode().put("type", "object"));
        Mockito.when(engine.getActiveTools(Mockito.any())).thenReturn(List.of(definition));
        Mockito.when(engine.resolveDefinition("external_test")).thenReturn(definition);
        AtomicInteger executions = new AtomicInteger();
        Mockito.when(engine.execute(Mockito.any(), Mockito.any()))
                .thenAnswer(
                        invocation -> {
                            executions.incrementAndGet();
                            return new ToolResult("external_test", "approval-call", true, "Done");
                        });
        AtomicInteger calls = new AtomicInteger();
        var hitl = Mockito.spy(new HitlRegistry());
        Mockito.doReturn(
                        new ApprovalDecision.Prompt(
                                VetoScenario.GENERIC,
                                List.of(VetoOption.ACCEPT_GENERIC, VetoOption.GENERIC_DECLINE),
                                null,
                                null))
                .when(hitl)
                .decide(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any());
        var service =
                serviceWith(
                        request ->
                                calls.getAndIncrement() == 0
                                        ? new VetoResponse(
                                                "Act",
                                                List.of(
                                                        new ToolCall(
                                                                "external_test",
                                                                Map.of(),
                                                                "approval-call")),
                                                null,
                                                null)
                                        : new VetoResponse(null, null, "Done", null),
                        2,
                        engine,
                        hitl);
        @NonNull AgentWaitStore waits = Mockito.mock();
        Mockito.when(waits.load(Mockito.any(), Mockito.anyString())).thenReturn(Optional.empty());
        AtomicReference<AgentWaitStore.@Nullable Wait> saved = new AtomicReference<>();
        Mockito.doAnswer(
                        invocation -> {
                            AgentWaitStore.Wait value = invocation.getArgument(2);
                            if (failSave && value == null)
                                throw new IllegalStateException("checkpoint unavailable");
                            if (value == null) assertFalse(Thread.currentThread().isInterrupted());
                            saved.set(value);
                            return null;
                        })
                .when(waits)
                .save(Mockito.any(), Mockito.anyString(), Mockito.any());
        service.attachWaitStore(waits);
        CountDownLatch awaiting = new CountDownLatch(1);
        Mockito.doAnswer(
                        invocation -> {
                            awaiting.countDown();
                            return invocation.callRealMethod();
                        })
                .when(hitl)
                .await(Mockito.anyString(), Mockito.anyString());
        service.submitNow("cancel-approval-wait", "Ask me", binding("System"));
        var agent = requireAgent(service.agent("cancel-approval-wait"));
        try {
            assertTrue(awaiting.await(5, TimeUnit.SECONDS));
            assertEquals("APPROVAL", agent.executionWaitReason());
            assertTrue(agent.cancelTask(agent.result(), Duration.ofSeconds(5)));
            assertFalse(agent.result().get().success());
            assertEquals(0, executions.get());
            assertEquals(1, calls.get());
            assertTrue(hitl.pendingFor(agent.id()).isEmpty());
            if (failSave) {
                assertEquals("APPROVAL", agent.executionWaitReason());
                assertTrue(saved.get() != null);
            } else {
                assertNull(agent.executionWaitReason());
                assertNull(saved.get());
            }
            assertTrue(
                    agent.history().stream()
                            .anyMatch(
                                    turn ->
                                            turn.type() == TurnType.EXECUTION_ERROR
                                                    && "CANCELLED"
                                                            .equals(
                                                                    turn.payload()
                                                                            .get("outcome"))));
        } finally {
            service.remove("cancel-approval-wait");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVAL_SAVE", "RESOLUTION_SAVE", "NONE", "DECLINED", "POLICY"})
    void approvalCheckpointControlsActualToolExecution(@NonNull String failure) throws Exception {
        @NonNull ToolEngine engine = Mockito.mock();
        var definition =
                new RemoteToolDefinition(
                        "external_test",
                        "Test external action",
                        "test",
                        new ObjectMapper().createObjectNode().put("type", "object"));
        Mockito.when(engine.getActiveTools(Mockito.any())).thenReturn(List.of(definition));
        Mockito.when(engine.resolveDefinition("external_test")).thenReturn(definition);
        AtomicInteger executions = new AtomicInteger();
        Mockito.when(engine.execute(Mockito.any(), Mockito.any()))
                .thenAnswer(
                        invocation -> {
                            executions.incrementAndGet();
                            return new ToolResult("external_test", "approval-call", true, "Done");
                        });
        AtomicInteger calls = new AtomicInteger();
        var hitl = Mockito.spy(new HitlRegistry());
        Mockito.doReturn(
                        new ApprovalDecision.Prompt(
                                VetoScenario.GENERIC,
                                List.of(VetoOption.ACCEPT_GENERIC, VetoOption.GENERIC_DECLINE),
                                null,
                                null))
                .when(hitl)
                .decide(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any());
        var service =
                serviceWith(
                        request ->
                                calls.getAndIncrement() == 0
                                        ? new VetoResponse(
                                                "Act",
                                                List.of(
                                                        new ToolCall(
                                                                "external_test",
                                                                Map.of(),
                                                                "approval-call")),
                                                null,
                                                null)
                                        : new VetoResponse(null, null, "Done", null),
                        2,
                        engine,
                        hitl);
        @NonNull AgentWaitStore waits = Mockito.mock();
        Mockito.when(waits.load(Mockito.any(), Mockito.anyString())).thenReturn(Optional.empty());
        AtomicReference<AgentWaitStore.@Nullable Wait> saved = new AtomicReference<>();
        Mockito.doAnswer(
                        invocation -> {
                            AgentWaitStore.Wait value = invocation.getArgument(2);
                            if ((failure.equals("APPROVAL_SAVE") && value != null)
                                    || (failure.equals("RESOLUTION_SAVE") && value == null))
                                throw new IllegalStateException("checkpoint unavailable");
                            saved.set(value);
                            return null;
                        })
                .when(waits)
                .save(Mockito.any(), Mockito.anyString(), Mockito.any());
        service.attachWaitStore(waits);
        if (failure.equals("POLICY"))
            Mockito.doReturn(new ApprovalDecision.Refused("Test policy denial"))
                    .when(hitl)
                    .decide(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any());
        AtomicInteger prompts = new AtomicInteger();
        try {
            var result =
                    service.submit(
                            "approval-checkpoint",
                            "Ask me",
                            binding("System"),
                            EPISODE_TIMEOUT,
                            null,
                            prompt -> {
                                prompts.incrementAndGet();
                                assertEquals(
                                        AgentWaitStore.Reason.APPROVAL,
                                        Nullness.requireNonNull(saved.get()).reason());
                                if (failure.equals("POLICY")) {
                                    assertEquals(Danger.CRITICAL, prompt.danger());
                                    assertEquals(
                                            List.of(VetoOption.EXEC_DECLINE), prompt.options());
                                }
                                assertTrue(
                                        hitl.resolveOption(
                                                prompt.agentId(),
                                                prompt.callId(),
                                                (failure.equals("DECLINED")
                                                                ? VetoOption.GENERIC_DECLINE
                                                                : VetoOption.ACCEPT_GENERIC)
                                                        .name()));
                            },
                            null,
                            null,
                            null);
            assertEquals(failure.equals("NONE"), result.success(), result.message());
            assertEquals(failure.equals("NONE") ? 1 : 0, executions.get());
            assertEquals(failure.equals("APPROVAL_SAVE") ? 0 : 1, prompts.get());
            if (failure.equals("DECLINED") || failure.equals("POLICY")) {
                assertEquals(1, calls.get(), "Refusal must not automatically retry");
                assertEquals(
                        failure.equals("DECLINED"),
                        result.message().contains("Approval was requested"),
                        result.message());
            }
            if (failure.equals("RESOLUTION_SAVE"))
                assertEquals(
                        AgentWaitStore.Reason.APPROVAL,
                        Nullness.requireNonNull(saved.get()).reason());
        } finally {
            service.remove("approval-checkpoint");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVAL", "BREAKER", "QUESTION"})
    void recoveredExecutionWaitBlocksNotificationsAndNeedsExplicitUserInput(@NonNull String reason)
            throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        List<VetoRequest> requests = new CopyOnWriteArrayList<>();
        var service =
                serviceWith(
                        request -> {
                            requests.add(request);
                            called.countDown();
                            return new VetoResponse(null, null, "Handled", null);
                        });
        @NonNull AgentWaitStore waits = Mockito.mock();
        Mockito.when(waits.load(Mockito.any(), Mockito.anyString()))
                .thenReturn(
                        Optional.of(
                                new AgentWaitStore.Wait(
                                        Nullness.requireNonNull(
                                                AgentWaitStore.Reason.valueOf(reason)),
                                        "old-request")));
        service.attachWaitStore(waits);
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
                                ToolResultPresentationMode.BASIC,
                                false);
        @NonNull MonitorService monitor = Mockito.mock();
        agent.attachMonitor(monitor);
        try {
            assertEquals(AgentState.WAITING, agent.state());
            Mockito.verify(waits).load(session, id);
            assertEquals(reason, agent.executionWaitReason());
            agent.resume();
            agent.signalMonitor();
            assertFalse(called.await(150, TimeUnit.MILLISECONDS));
            Mockito.verify(monitor, Mockito.never())
                    .pending(Mockito.anyString(), Mockito.anyString());
            Mockito.doThrow(new IllegalStateException("save failed"))
                    .when(waits)
                    .save(session, id, null);
            agent.submit("continue");
            assertFalse(agent.await(EPISODE_TIMEOUT).success());
            assertEquals(0, requests.size());
            assertEquals(reason, agent.executionWaitReason());
            Mockito.doNothing().when(waits).save(session, id, null);
            agent.submit("continue");
            assertTrue(agent.await(EPISODE_TIMEOUT).success());
            assertEquals(1, requests.size());
            assertTrue(agent.executionWaitReason() == null);
            if (reason.equals("BREAKER"))
                assertTrue(requests.get(0).messages().toString().contains("Explain TCP"));
        } finally {
            service.remove(session.toString());
            assertTrue(agent.awaitTermination(Duration.ofSeconds(5)));
        }
    }

    @Test
    void restoredPauseKeepsNotificationPendingUntilExplicitResume() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        var service =
                serviceWith(
                        request -> {
                            called.countDown();
                            return new VetoResponse(null, null, "Notification handled", null);
                        });
        @NonNull AgentPauseStore pauses = Mockito.mock();
        Mockito.when(pauses.load(Mockito.any(), Mockito.anyString())).thenReturn(true);
        service.attachPauseStore(pauses);
        @NonNull KeysteadVault vault = Mockito.mock();
        service.attachMonitorVault(vault);
        UUID session = UUID.randomUUID();
        String id = UUID.randomUUID().toString();
        var agent =
                (VetoAgent)
                        service.getOrCreateAgent(
                                session.toString(),
                                id,
                                binding("System"),
                                List.of(),
                                UUID.randomUUID(),
                                "owner",
                                "D:/IdeaProjects/veto/work/tmp/unfinished-group",
                                0,
                                ToolResultPresentationMode.BASIC,
                                false);
        var event =
                new MonitorRecord.Event(
                        "paused:timer", "timer", "TIME_ONCE", "Review", Instant.now());
        List<MonitorRecord.@NonNull Event> pending = new CopyOnWriteArrayList<>(List.of(event));
        @NonNull MonitorService monitor = Mockito.mock();
        Mockito.when(monitor.pending(id, session.toString()))
                .thenAnswer(invocation -> List.copyOf(pending));
        Mockito.doAnswer(
                        invocation -> {
                            pending.clear();
                            return null;
                        })
                .when(monitor)
                .acknowledge(id, event);
        agent.attachMonitor(monitor);
        try {
            agent.signalMonitor();
            assertFalse(called.await(150, TimeUnit.MILLISECONDS));
            assertEquals(List.of(event), pending);
            Mockito.verify(monitor, Mockito.never()).activationStarted(id, event);
            agent.resume();
            assertFalse(called.await(150, TimeUnit.MILLISECONDS));
            assertEquals(List.of(event), pending);
            Mockito.when(vault.isUnlocked("owner")).thenReturn(true);
            agent.signalMonitor();
            assertTrue(called.await(5, TimeUnit.SECONDS));
            assertTrue(agent.await(EPISODE_TIMEOUT).success());
            Mockito.verify(monitor).activationStarted(id, event);
            assertTrue(pending.isEmpty());
        } finally {
            service.remove(session.toString());
            assertTrue(agent.awaitTermination(Duration.ofSeconds(5)));
        }
    }

    @Test
    void restoredPauseHoldsWorkUntilResumeIsSaved() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var service =
                serviceWith(
                        request -> {
                            calls.incrementAndGet();
                            return new VetoResponse(null, null, "done", null);
                        });
        @NonNull AgentPauseStore pauses = Mockito.mock();
        Mockito.when(pauses.load(Mockito.any(), Mockito.anyString())).thenReturn(true);
        service.attachPauseStore(pauses);
        UUID session = UUID.randomUUID();
        String id = UUID.randomUUID().toString();
        var agent =
                (VetoAgent)
                        service.getOrCreateAgent(
                                session.toString(),
                                id,
                                binding("System"),
                                List.of(),
                                UUID.randomUUID(),
                                null,
                                "D:/IdeaProjects/veto/work/tmp/unfinished-group",
                                0,
                                ToolResultPresentationMode.BASIC,
                                false);
        try {
            assertEquals(AgentState.PAUSED, agent.state());
            Mockito.verify(pauses).load(session, id);
            agent.submit("Review");
            assertThrows(
                    ToolDocs.nonNullClass(TimeoutException.class),
                    () -> agent.result().get(150, TimeUnit.MILLISECONDS));
            assertEquals(0, calls.get());
            Mockito.doThrow(new IllegalStateException("storage unavailable"))
                    .when(pauses)
                    .save(session, id, false);
            assertThrows(IllegalStateException.class, agent::resume);
            assertEquals(AgentState.PAUSED, agent.state());
            assertFalse(agent.result().isDone());
            assertEquals(0, calls.get());
            Mockito.doNothing().when(pauses).save(session, id, false);
            agent.resume();
            assertTrue(agent.await(EPISODE_TIMEOUT).success());
            assertEquals(1, calls.get());
        } finally {
            service.remove(session.toString());
            assertTrue(agent.awaitTermination(Duration.ofSeconds(5)));
        }
    }

    @Test
    void pauseDuringModelCallHoldsCorrectionAndCancellationStopsTheWait() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var service =
                serviceWith(
                        request -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            try {
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                            throw new ModelSchemaException("retry needed");
                        });
        service.submitNow("pause-active", "Review", binding("System"));
        var agent = requireAgent(service.agent("pause-active"));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            agent.pause();
            release.countDown();
            assertThrows(
                    ToolDocs.nonNullClass(TimeoutException.class),
                    () -> agent.result().get(150, TimeUnit.MILLISECONDS));
            assertEquals(1, calls.get());
            assertTrue(agent.cancelTask(agent.result(), Duration.ofSeconds(5)));
            assertFalse(agent.result().get().success());
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            service.remove("pause-active");
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
        @NonNull MonitorRepository repository = Mockito.mock();
        Mockito.when(repository.findAll())
                .thenReturn(
                        List.of(
                                new MonitorEntity(
                                        snapshot.id(), mapper.writeValueAsString(snapshot))));
        var monitor =
                new MonitorService(
                        repository, mapper, new GroupRegistry(), new SessionAgentRegistry());
        monitor.restore();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var service =
                serviceWith(
                        request -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("Not released");
                            } catch (InterruptedException error) {
                                throw new IllegalStateException(error);
                            }
                            if (!success) throw new IllegalStateException("Provider unavailable");
                            return new VetoResponse(null, null, "Review finished", null);
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
                                                    TurnType.MONITOR_EVENT,
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
                                    ToolResultPresentationMode.BASIC,
                                    false);
            agent.attachMonitor(monitor);
            agent.signalMonitor();
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
                            .filter(t -> t.type() == TurnType.MONITOR_EVENT)
                            .filter(t -> !t.payload().containsKey("restored_from_turn"))
                            .count());
            assertEquals(
                    1,
                    HistoryProjection.effective(agent.history()).stream()
                            .filter(t -> t.type() == TurnType.MONITOR_EVENT)
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
        var repository = Mockito.mock(ToolDocs.nonNullClass(RequestContinuationRepository.class));
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
                request -> {
                    assertFalse(
                            durable.isEmpty(), "Budget must be saved before provider execution");
                    requests.add(request);
                    return new VetoResponse(null, null, "done", null);
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
                ToolResultPresentationMode.BASIC,
                false);
        first.submit(session.toString(), "Review apples", binding("System"), EPISODE_TIMEOUT);
        var original = requireAgent(first.agent(session.toString()));
        String requestId = requestIdentity(original);
        List<TurnRecord> history = original.history();
        String agentId = original.id();
        first.remove(session.toString());
        assertTrue(original.awaitTermination(Duration.ofSeconds(5)));
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
                                    ToolResultPresentationMode.BASIC,
                                    false);
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
            @NonNull MonitorService monitors = Mockito.mock();
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
            agent.attachMonitor(monitors);
            agent.signalMonitor();
            assertTrue(acknowledged.await(5, TimeUnit.SECONDS));
            assertEquals(maxCalls == 2, agent.await(EPISODE_TIMEOUT).success());
            assertEquals(maxCalls, requests.size());
            assertEquals(
                    maxCalls,
                    store.load(session, agentId, requestId).orElseThrow().consumedCalls());
            assertEquals(
                    "Review apples", store.load(session, agentId, requestId).orElseThrow().task());
            assertTrue(
                    agent.history().stream()
                            .filter(t -> t.type() == TurnType.MONITOR_EVENT)
                            .anyMatch(
                                    t ->
                                            String.valueOf(t.payload().get("content"))
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
                        request -> {
                            calls.incrementAndGet();
                            return new VetoResponse(null, null, "unexpected", null);
                        });
        @NonNull RequestContinuationStore store = Mockito.mock();
        Mockito.doThrow(new IllegalStateException("Checkpoint unavailable"))
                .when(store)
                .save(
                        Mockito.any(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyLong());
        service.attachContinuationStore(store);
        @NonNull TurnRecordRepository records = Mockito.mock();
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

    @Test
    void restoredTimerOccurrenceCannotAcquireAnotherBudget() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var service =
                serviceWith(
                        request -> {
                            calls.incrementAndGet();
                            return new VetoResponse(null, null, "unexpected", null);
                        },
                        1L);
        UUID session = UUID.randomUUID();
        String agentId = UUID.randomUUID().toString();
        @NonNull RequestContinuationStore store = Mockito.mock();
        Mockito.when(store.load(session, agentId, "monitor:timer-event"))
                .thenReturn(
                        Optional.of(
                                new RequestContinuationStore.Checkpoint(
                                        "Original timer purpose", 1)));
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
                                    ToolResultPresentationMode.BASIC,
                                    false);
            var event =
                    new MonitorRecord.Event(
                            "timer-event", "timer", "TIME_ONCE", "Timer fired", Instant.now());
            @NonNull MonitorService monitors = Mockito.mock();
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
            agent.attachMonitor(monitors);
            agent.signalMonitor();
            assertTrue(acknowledged.await(5, TimeUnit.SECONDS));
            assertFalse(agent.await(EPISODE_TIMEOUT).success());
            assertEquals(0, calls.get());
            Mockito.verify(store, Mockito.never())
                    .save(
                            Mockito.any(),
                            Mockito.anyString(),
                            Mockito.anyString(),
                            Mockito.anyString(),
                            Mockito.anyLong());
        } finally {
            service.remove(session.toString());
        }
    }

    @Test
    void sessionActivationRestoresTeamAndRunsNewWorkOnOriginalMateWithoutReplay() throws Exception {
        List<VetoRequest> requests = new CopyOnWriteArrayList<>();
        var service =
                serviceWith(
                        request -> {
                            requests.add(request);
                            return new VetoResponse(null, null, "new report", null);
                        });
        UUID session = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        String leaderId = UUID.randomUUID().toString();
        String mateId = UUID.randomUUID().toString();
        String idleId = UUID.randomUUID().toString();
        Blackboard board = new Blackboard();
        GroupRegistry registry = new GroupRegistry();
        GroupOrchestrator orchestrator = new GroupOrchestrator(registry, board);
        var repository = Mockito.mock(ToolDocs.nonNullClass(GroupHistoryRepository.class));
        List<GroupHistoryEntity> rows = new ArrayList<>();
        Mockito.when(repository.save(Mockito.any()))
                .thenAnswer(
                        invocation -> {
                            GroupHistoryEntity row = invocation.getArgument(0);
                            if (row == null) throw new AssertionError("Missing row");
                            rows.add(row);
                            return row;
                        });
        Mockito.when(repository.findBySessionIdOrderByRecordedAtAsc(session.toString()))
                .thenReturn(rows);
        GroupHistoryStore store =
                new GroupHistoryStore(repository, new ObjectMapper().findAndRegisterModules());
        Group old =
                Group.create(
                                leaderId,
                                user.toString(),
                                "saved team",
                                board,
                                new ExecutionDag(UUID.randomUUID(), List.of()),
                                "owner",
                                null,
                                ToolResultPresentationMode.BASIC,
                                false,
                                session)
                        .withMate(mateId, "review")
                        .withMate(idleId, "analysis");
        old =
                old.withDag(
                        new ExecutionDag(
                                old.groupId(),
                                List.of(
                                        new DagNode(
                                                "old-task",
                                                "do not replay",
                                                mateId,
                                                "review",
                                                Set.of(),
                                                DagNode.NodeState.RUNNING,
                                                new DagNode.ResultNone(),
                                                1,
                                                "old-dispatch",
                                                "old-request"))));
        store.save(old);
        registry.attachHistory(store);
        var loader = Mockito.mock(ToolDocs.nonNullClass(SessionHistoryLoader.class));
        AtomicInteger historyLoads = new AtomicInteger();
        Mockito.when(loader.load(Mockito.eq(session.toString()), Mockito.anyString()))
                .thenAnswer(
                        invocation -> {
                            if (historyLoads.incrementAndGet() == 2)
                                throw new IllegalStateException("Temporary history read failure");
                            return mateId.equals(invocation.getArgument(1))
                                    ? List.of(TurnRecord.userPrompt(1, "prior member history"))
                                    : List.of();
                        });
        var tiers = Mockito.mock(ToolDocs.nonNullClass(ModelTierRegistry.class));
        Mockito.when(tiers.resolve("owner", ModelTier.TOP))
                .thenReturn(
                        new ModelBinding(ProviderType.DEEPSEEK, "leader-current", "key", 0, 4096));
        var spawner =
                new GroupSpawner(
                        board,
                        registry,
                        orchestrator,
                        new MateBreakerRegistry(),
                        new SkillsetProperties(),
                        50,
                        "MID",
                        "System",
                        (persona, mateBinding) ->
                                service.createMate(
                                        persona,
                                        binding("System"),
                                        user,
                                        "owner",
                                        Nullness.requireNonNull(mateBinding.workspace()),
                                        ToolResultPresentationMode.BASIC,
                                        false,
                                        session));
        Object owned = ReflectionTestUtils.getField(service, "sessionAgents");
        if (!(owned instanceof SessionAgentRegistry agents))
            throw new AssertionError("Missing registry");
        service.attachGroupRecovery(
                new GroupRecoveryService(
                        store,
                        registry,
                        board,
                        spawner,
                        loader,
                        agents,
                        new LeaderBinding("TOP", "System", tiers),
                        new RoleToolFilter(new TestToolEngine())));
        try {
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            service.getOrCreateAgent(
                                    session.toString(),
                                    leaderId,
                                    binding("System"),
                                    List.of(TurnRecord.userPrompt(1, "prior leader history")),
                                    user,
                                    "owner",
                                    "D:/IdeaProjects/veto/work/tmp/unfinished-group",
                                    0,
                                    ToolResultPresentationMode.BASIC,
                                    false));
            assertEquals(
                    2,
                    agents.agents(session).size(),
                    "First recovered member remains available for retry");
            Agent leader =
                    service.getOrCreateAgent(
                            session.toString(),
                            leaderId,
                            binding("System"),
                            List.of(TurnRecord.userPrompt(1, "prior leader history")),
                            user,
                            "owner",
                            "D:/IdeaProjects/veto/work/tmp/unfinished-group",
                            0,
                            ToolResultPresentationMode.BASIC,
                            false);
            assertEquals(Role.LEADER, leader.persona().role());
            assertEquals(3, historyLoads.get(), "Retry loads only the missing member");
            Group restored = Nullness.requireNonNull(registry.get(old.groupId()));
            assertEquals(old.mates(), restored.mates());
            assertEquals(DagNode.NodeState.INTERRUPTED, restored.dag().nodes().getFirst().state());
            orchestrator.tick(old.groupId());
            assertEquals(
                    Group.GroupState.ACTIVE,
                    Nullness.requireNonNull(registry.get(old.groupId())).state());
            assertTrue(requests.isEmpty(), "Recovery must not execute interrupted work");
            var mate =
                    agents.agents(session).stream()
                            .filter(entry -> entry.agent().id().equals(mateId))
                            .findFirst()
                            .orElseThrow()
                            .agent();
            assertTrue(
                    mate.history().stream()
                            .anyMatch(
                                    turn ->
                                            "prior member history"
                                                    .equals(turn.payload().get("content"))));
            orchestrator.addNode(
                    old.groupId(),
                    "new-task",
                    "new review",
                    "review",
                    Set.of(),
                    mateId,
                    false,
                    "new-request");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                restored = Nullness.requireNonNull(orchestrator.tick(old.groupId()));
                if (restored.dag().nodes().stream()
                        .anyMatch(
                                node ->
                                        node.nodeId().equals("new-task")
                                                && node.state() == DagNode.NodeState.VERIFIED))
                    break;
                Thread.sleep(20);
            }
            assertEquals(1, requests.size());
            var recoveryMessages =
                    requests.getFirst().messages().stream()
                            .filter(
                                    message ->
                                            message.content()
                                                    .startsWith("[Runtime recovery observation]"))
                            .toList();
            assertEquals(
                    1,
                    recoveryMessages.size(),
                    "Partial recovery retry must not duplicate context");
            String recovery = recoveryMessages.getFirst().content();
            assertTrue(recovery.contains("old-task"));
            assertTrue(recovery.contains("old-dispatch"));
            assertTrue(recovery.contains("old-request"));
            assertFalse(recovery.contains("new-request"));
            assertFalse(
                    mate.history().stream()
                            .anyMatch(
                                    turn ->
                                            turn.payload().values().stream()
                                                    .anyMatch(
                                                            value ->
                                                                    value instanceof String text
                                                                            && text.contains(
                                                                                    "[Runtime recovery observation]"))));
            assertTrue(
                    restored.dag().nodes().stream()
                            .anyMatch(
                                    node ->
                                            node.nodeId().equals("new-task")
                                                    && node.state() == DagNode.NodeState.VERIFIED));
            assertSame(
                    leader,
                    service.getOrCreateAgent(
                            session.toString(),
                            leaderId,
                            binding("System"),
                            List.of(),
                            user,
                            "owner",
                            "D:/IdeaProjects/veto/work/tmp/unfinished-group",
                            0,
                            ToolResultPresentationMode.BASIC,
                            false));
            assertEquals(3, agents.agents(session).size());
            service.submitNow(session.toString(), "Follow-up", binding("System"));
            assertTrue(leader.await(EPISODE_TIMEOUT).success());
            assertEquals("leader-current", requests.getLast().modelName());
            assertFalse(
                    requests.getLast().messages().stream()
                            .anyMatch(
                                    message ->
                                            message.content()
                                                    .startsWith("[Runtime recovery observation]")),
                    "Mate recovery context must not leak into the Leader");
        } finally {
            spawner.disband(old.groupId());
            service.remove(session.toString());
        }
    }

    @Test
    void completedResultDoesNotConfirmTaskExitWhileCallbackStillRuns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var service = serviceWith(request -> new VetoResponse(null, null, "done", null));
        try {
            service.submit("callback-exit", "Warm up", binding("System"), EPISODE_TIMEOUT);
            var agent = requireAgent(service.agent("callback-exit"));
            agent.submit(
                    "Next",
                    result -> {
                        entered.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
            var task = agent.result();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(task.isDone());
            assertFalse(agent.cancelTask(task, Duration.ofMillis(20)));
            release.countDown();
            assertTrue(agent.cancelTask(task, Duration.ofSeconds(5)));
        } finally {
            release.countDown();
            service.remove("callback-exit");
        }
    }

    @Test
    void restoredCancellationPreventsLateProcessReasoningButAllowsNewWork() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var service =
                serviceWith(
                        request -> {
                            calls.incrementAndGet();
                            return new VetoResponse(null, null, "New task done", null);
                        });
        UUID session = UUID.randomUUID();
        String agentId = UUID.randomUUID().toString();
        String oldRequest = "cancelled-request";
        @NonNull RequestContinuationStore store = Mockito.mock();
        Mockito.when(store.load(session, agentId, oldRequest))
                .thenReturn(
                        Optional.of(
                                new RequestContinuationStore.Checkpoint("Old cancelled task", 1)));
        service.attachContinuationStore(store);
        List<TurnRecord> restored =
                List.of(
                        new TurnRecord(
                                1,
                                TurnType.EXECUTION_ERROR,
                                Map.of(
                                        "content",
                                        "Task cancelled",
                                        "outcome",
                                        "CANCELLED",
                                        "requestId",
                                        oldRequest),
                                null));
        try {
            var agent =
                    (VetoAgent)
                            service.getOrCreateAgent(
                                    session.toString(),
                                    agentId,
                                    binding("System"),
                                    restored,
                                    UUID.randomUUID(),
                                    null,
                                    "D:/IdeaProjects/veto/work/tmp/unfinished-group",
                                    0,
                                    ToolResultPresentationMode.BASIC,
                                    false);
            var event =
                    new MonitorRecord.Event(
                            "old-process-exit",
                            "process",
                            "PROCESS_EVENT",
                            "Original process exited",
                            Instant.now(),
                            oldRequest,
                            "process-instance");
            @NonNull MonitorService monitors = Mockito.mock();
            CountDownLatch cancelled = new CountDownLatch(1);
            Mockito.when(monitors.pending(agentId, session.toString()))
                    .thenAnswer(
                            invocation -> cancelled.getCount() == 0 ? List.of() : List.of(event));
            Mockito.doAnswer(
                            invocation -> {
                                cancelled.countDown();
                                return null;
                            })
                    .when(monitors)
                    .activationCancelled(agentId, session.toString(), event);
            agent.attachMonitor(monitors);
            agent.signalMonitor();
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            assertEquals(0, calls.get());
            agent.submit("A new task");
            assertTrue(agent.await(EPISODE_TIMEOUT).success());
            assertEquals(1, calls.get());
            Mockito.verify(monitors, Mockito.never()).activationStarted(agentId, event);
            assertTrue(
                    agent.history().stream()
                            .noneMatch(
                                    turn ->
                                            turn.type() == TurnType.MONITOR_EVENT
                                                    && event.id()
                                                            .equals(
                                                                    turn.payload()
                                                                            .get("eventId"))));
        } finally {
            service.remove(session.toString());
        }
    }

    @Test
    void cancellationWaitsForExecutionExitAndSameAgentCanWorkAgain() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger interruptCount = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        var service =
                serviceWith(
                        request -> {
                            if (calls.incrementAndGet() == 1) {
                                entered.countDown();
                                boolean released = false;
                                while (!released) {
                                    try {
                                        released = release.await(5, TimeUnit.SECONDS);
                                    } catch (InterruptedException ignored) {
                                        /* Simulate a provider that ignores cancellation. */
                                        interruptCount.incrementAndGet();
                                        interrupted.countDown();
                                    }
                                }
                            } else {
                                assertTrue(
                                        request.messages().stream()
                                                .anyMatch(
                                                        message ->
                                                                message.content()
                                                                        .contains(
                                                                                "[Runtime cancellation]")));
                            }
                            return new VetoResponse(null, null, "completed", null);
                        });
        try {
            service.submitNow("cancel-task", "First task", binding("System"));
            var agent = requireAgent(service.agent("cancel-task"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var first = agent.result();
            assertFalse(agent.cancelTask(first, Duration.ofMillis(20)));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertFalse(agent.cancelTask(first, Duration.ofMillis(20)));
            assertEquals(
                    1,
                    interruptCount.get(),
                    "Repeated cancellation must not interrupt cleanup again");
            assertFalse(first.isDone(), "uncooperative execution must not be reported stopped");
            release.countDown();
            assertTrue(agent.cancelTask(first, Duration.ofSeconds(5)));
            assertFalse(first.get().success());
            assertTrue(
                    agent.history().stream()
                            .anyMatch(
                                    turn ->
                                            turn.type() == TurnType.EXECUTION_ERROR
                                                    && "CANCELLED"
                                                            .equals(turn.payload().get("outcome"))
                                                    && turn.payload().get("requestId")
                                                            instanceof String));
            assertFalse(
                    agent.history().stream()
                            .anyMatch(
                                    turn ->
                                            turn.type() == TurnType.ASSISTANT_RESPONSE
                                                    && "completed"
                                                            .equals(turn.payload().get("content"))),
                    "late answer must not be emitted");
            var event =
                    new MonitorRecord.Event(
                            "cancelled-process",
                            "process",
                            "PROCESS_EVENT",
                            "Process exited",
                            Instant.now(),
                            requestIdentity(agent),
                            "instance");
            @NonNull MonitorService monitors = Mockito.mock();
            CountDownLatch observationCancelled = new CountDownLatch(1);
            Mockito.when(monitors.pending(Mockito.eq(agent.id()), Mockito.anyString()))
                    .thenAnswer(
                            invocation ->
                                    observationCancelled.getCount() == 0
                                            ? List.of()
                                            : List.of(event));
            Mockito.doAnswer(
                            invocation -> {
                                observationCancelled.countDown();
                                return null;
                            })
                    .when(monitors)
                    .activationCancelled(
                            Mockito.eq(agent.id()), Mockito.anyString(), Mockito.eq(event));
            agent.attachMonitor(monitors);
            agent.signalMonitor();
            assertTrue(observationCancelled.await(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get(), "Cancelled request must not resume on process exit");
            agent.submit("Second task");
            assertTrue(agent.await(EPISODE_TIMEOUT).success());
            assertEquals(2, calls.get());
            assertNotEquals(AgentState.TERMINATED, agent.state());
        } finally {
            release.countDown();
            service.remove("cancel-task");
        }
    }

    @Test
    void taskCancellationOverridesWrappedProviderFailure() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        var service =
                serviceWith(
                        request -> {
                            entered.countDown();
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException error) {
                                throw new LlmException("provider call failed", error, false);
                            }
                            return new VetoResponse(null, null, "unexpected", null);
                        });
        try {
            service.submitNow("wrapped-cancel", "Cancelled work", binding("System"));
            var agent = requireAgent(service.agent("wrapped-cancel"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(agent.cancelTask(agent.result(), Duration.ofSeconds(5)));
            var failures =
                    agent.history().stream()
                            .filter(turn -> turn.type() == TurnType.EXECUTION_ERROR)
                            .toList();
            assertEquals(1, failures.size());
            assertEquals("CANCELLED", failures.getFirst().payload().get("outcome"));
            assertFalse(
                    agent.history().stream()
                            .anyMatch(turn -> turn.type() == TurnType.TOOL_RESPONSE));
            assertFalse(
                    String.valueOf(failures.getFirst().payload().get("content"))
                            .contains("provider call failed"));
        } finally {
            service.remove("wrapped-cancel");
        }
    }

    @Test
    void successiveUserRequestsHaveDistinctDurableIdentities() throws Exception {
        var service = serviceWith(request -> new VetoResponse(null, null, "done", null));
        try {
            service.submit("request-ids", "First task", binding("System"), EPISODE_TIMEOUT);
            service.submit("request-ids", "Second task", binding("System"), EPISODE_TIMEOUT);
            var agent = requireAgent(service.agent("request-ids"));
            var requests =
                    agent.history().stream()
                            .filter(t -> t.type() == TurnType.USER_PROMPT)
                            .map(t -> t.payload().get("requestId"))
                            .toList();
            assertEquals(2, requests.size());
            assertTrue(requests.get(0) instanceof String id && !id.isBlank());
            assertTrue(requests.get(1) instanceof String id && !id.isBlank());
            assertNotEquals(requests.get(0), requests.get(1));
        } finally {
            service.remove("request-ids");
        }
    }

    @Test
    void citationCorrectionHasAnOpportunityAfterSchemaCorrections() throws Exception {
        var calls = new AtomicInteger();
        var service =
                serviceWith(
                        request -> {
                            int call = calls.incrementAndGet();
                            if (call <= 2) throw new ModelSchemaException("Invalid JSON shape");
                            return new VetoResponse(
                                    null,
                                    null,
                                    "[Meeting](cite:meeting)",
                                    null,
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
                serviceWith(
                        request -> {
                            if (calls.incrementAndGet() > 1)
                                throw new ModelSchemaException("Invalid correction");
                            return new VetoResponse(
                                    null,
                                    null,
                                    "[Meeting](cite:meeting)",
                                    null,
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
                serviceWith(
                        request -> {
                            calls.incrementAndGet();
                            return new VetoResponse(
                                    null,
                                    null,
                                    "The meeting is [at 14:30](cite:meeting).",
                                    null,
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
                serviceWith(
                        request -> {
                            boolean first = calls.incrementAndGet() == 1;
                            return new VetoResponse(
                                    null,
                                    null,
                                    "[Meeting](cite:meeting)",
                                    null,
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
                serviceWith(
                        request -> {
                            if (calls.incrementAndGet() == 1)
                                throw new ModelSchemaException("try again");
                            if (calls.get() == 3)
                                return new VetoResponse(null, null, "No citation", null);
                            return new VetoResponse(
                                    null,
                                    null,
                                    "[Meeting](cite:meeting)",
                                    null,
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
                        request -> {
                            throw new NoClassDefFoundError("ToolErrors");
                        });
        var result =
                service.submit("linkage-failure", "Answer", binding("System"), EPISODE_TIMEOUT);
        assertFalse(result.success());
        assertTrue(result.message().contains("ToolErrors"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESOURCE_EVENT", "TIME_ONCE"})
    void directPromptWaitsForGroupMonitorCompletion(@NonNull String kind) throws Exception {
        var calls = new AtomicInteger();
        var parked = new CountDownLatch(1);
        var directDone = new CountDownLatch(1);
        var groupWork = new AtomicBoolean(true);
        var eventPending = new AtomicBoolean(false);
        var service =
                serviceWith(
                        request -> {
                            int call = calls.incrementAndGet();
                            return new VetoResponse(null, null, "result-" + call, null);
                        });
        try {
            service.submit("direct-monitor", "Initial task", binding("System"), EPISODE_TIMEOUT);
            var agent = requireAgent(service.agent("direct-monitor"));
            @NonNull MonitorService monitors = Mockito.mock();
            var event =
                    new MonitorRecord.Event("done", "group", kind, "Group finished", Instant.now());
            Mockito.when(monitors.hasGroupWork(Mockito.eq(agent.id()), Mockito.anyString()))
                    .thenAnswer(
                            invocation -> {
                                boolean work = groupWork.get();
                                if (work) parked.countDown();
                                return work;
                            });
            Mockito.when(monitors.pending(agent.id(), agent.sessionId().toString()))
                    .thenAnswer(
                            invocation ->
                                    eventPending.get()
                                            ? List.of(
                                                    new MonitorRecord.Event(
                                                            event.id(),
                                                            event.monitorId(),
                                                            event.kind(),
                                                            event.content(),
                                                            event.occurredAt(),
                                                            requestIdentity(agent),
                                                            "dispatch"))
                                            : List.of());
            Mockito.doAnswer(
                            invocation -> {
                                eventPending.set(false);
                                return null;
                            })
                    .when(monitors)
                    .acknowledge(Mockito.eq(agent.id()), Mockito.any());
            agent.attachMonitor(monitors);
            agent.addMessageListener(
                    message -> {
                        if (message.equals("result-4")) directDone.countDown();
                    });
            agent.submit("Wait for group");
            var workflow = agent.result();
            assertTrue(parked.await(5, TimeUnit.SECONDS));
            agent.submitUserPrompt("User follow-up");
            assertSame(workflow, agent.result());
            groupWork.set(false);
            eventPending.set(true);
            agent.signalMonitor();
            assertEquals("result-3", workflow.get(5, TimeUnit.SECONDS).message());
            assertTrue(directDone.await(5, TimeUnit.SECONDS));
        } finally {
            service.remove("direct-monitor");
        }
    }

    @Test
    void directPromptQueuesWithoutReplacingWorkflowResultOrCallback() throws Exception {
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var directEntered = new CountDownLatch(1);
        var releaseDirect = new CountDownLatch(1);
        var thirdDone = new CountDownLatch(1);
        var callbacks = new AtomicInteger();
        var calls = new AtomicInteger();
        var service =
                serviceWith(
                        request -> {
                            int call = calls.incrementAndGet();
                            try {
                                if (call == 1) {
                                    firstEntered.countDown();
                                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                                }
                                if (call == 2) {
                                    directEntered.countDown();
                                    assertTrue(releaseDirect.await(5, TimeUnit.SECONDS));
                                }
                            } catch (InterruptedException error) {
                                throw new AssertionError(error);
                            }
                            return new VetoResponse(null, null, "result-" + call, null);
                        });
        try {
            service.submitNow("direct-user", "Group task", binding("System"));
            var agent = requireAgent(service.agent("direct-user"));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            var workflow = agent.result();
            agent.submitUserPrompt("User follow-up");
            assertSame(workflow, agent.result());
            releaseFirst.countDown();
            assertEquals("result-1", workflow.get(5, TimeUnit.SECONDS).message());
            assertTrue(directEntered.await(5, TimeUnit.SECONDS));
            agent.submit(
                    "Next group task",
                    result -> {
                        callbacks.incrementAndGet();
                        thirdDone.countDown();
                    });
            var nextWorkflow = agent.result();
            releaseDirect.countDown();
            assertTrue(thirdDone.await(5, TimeUnit.SECONDS));
            assertEquals("result-3", nextWorkflow.get(5, TimeUnit.SECONDS).message());
            assertEquals(1, callbacks.get());
            assertTrue(
                    agent.history().stream()
                            .anyMatch(
                                    turn ->
                                            turn.type() == TurnType.USER_PROMPT
                                                    && "User follow-up"
                                                            .equals(
                                                                    turn.payload()
                                                                            .get("content"))));
        } finally {
            releaseFirst.countDown();
            releaseDirect.countDown();
            service.remove("direct-user");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void monitorWakeUsesObservationAndSameRunnerWithoutFakeUserPrompt(boolean retryAcknowledgement)
            throws Exception {
        var seen = new CopyOnWriteArrayList<VetoRequest>();
        var resumed = new CountDownLatch(1);
        var service =
                serviceWith(
                        request -> {
                            seen.add(request);
                            if (seen.size() > 1) resumed.countDown();
                            return new VetoResponse(null, null, "Done", null);
                        });
        service.submit("monitor-wake", "Initial task", binding("System"), EPISODE_TIMEOUT);
        var agent = requireAgent(service.agent("monitor-wake"));
        @NonNull MonitorService monitors = Mockito.mock();
        var event =
                new MonitorRecord.Event(
                        "wake",
                        "timer",
                        "TIME_ONCE",
                        "Scheduled wake-up: review the result",
                        Instant.now());
        var pending = new AtomicBoolean(true);
        var failAcknowledgement = new AtomicBoolean(retryAcknowledgement);
        var acknowledgementFailed = new CountDownLatch(1);
        Mockito.when(monitors.pending(agent.id(), agent.sessionId().toString()))
                .thenAnswer(call -> pending.get() ? List.of(event) : List.of());
        Mockito.doAnswer(
                        call -> {
                            if (failAcknowledgement.getAndSet(false)) {
                                acknowledgementFailed.countDown();
                                throw new IllegalStateException("acknowledgement storage failed");
                            }
                            pending.set(false);
                            return null;
                        })
                .when(monitors)
                .acknowledge(agent.id(), event);
        agent.attachMonitor(monitors);
        try {
            agent.signalMonitor();
            if (retryAcknowledgement) {
                assertTrue(acknowledgementFailed.await(5, TimeUnit.SECONDS));
                assertFalse(agent.await(EPISODE_TIMEOUT).success());
                agent.signalMonitor();
            }
            assertTrue(resumed.await(5, TimeUnit.SECONDS));
            assertTrue(agent.await(EPISODE_TIMEOUT).success());
            assertEquals(
                    1,
                    agent.history().stream().filter(t -> t.type() == TurnType.USER_PROMPT).count());
            assertEquals(
                    1,
                    agent.history().stream()
                            .filter(t -> t.type() == TurnType.MONITOR_EVENT)
                            .count());
            assertTrue(
                    seen.getLast().messages().stream()
                            .anyMatch(m -> m.content().contains("Scheduled wake-up")));
        } finally {
            agent.terminate();
        }
    }

    @Test
    void groupNotificationCannotResetEpisodeBudget() throws Exception {
        var calls = new AtomicInteger();
        var service =
                serviceWith(
                        request -> {
                            calls.incrementAndGet();
                            return new VetoResponse(null, null, "Done", null);
                        },
                        1L);
        service.submit("monitor-budget", "Initial task", binding("System"), EPISODE_TIMEOUT);
        var agent = requireAgent(service.agent("monitor-budget"));
        @NonNull MonitorService monitors = Mockito.mock();
        var event =
                new MonitorRecord.Event(
                        "result", "group", "RESOURCE_EVENT", "Task completed", Instant.now());
        Mockito.when(monitors.pending(agent.id(), agent.sessionId().toString()))
                .thenReturn(List.of(event));
        var consumed = new CountDownLatch(1);
        Mockito.doAnswer(
                        call -> {
                            consumed.countDown();
                            return null;
                        })
                .when(monitors)
                .acknowledge(agent.id(), event);
        agent.attachMonitor(monitors);
        try {
            agent.signalMonitor();
            assertTrue(consumed.await(5, TimeUnit.SECONDS));
            assertFalse(agent.await(EPISODE_TIMEOUT).success());
            assertEquals(1, calls.get());
        } finally {
            agent.terminate();
        }
    }

    private static @NonNull String requestIdentity(@NonNull VetoAgent agent) {
        Object id =
                agent.history().stream()
                        .filter(t -> t.type() == TurnType.USER_PROMPT)
                        .toList()
                        .getLast()
                        .payload()
                        .get("requestId");
        if (id instanceof String value) return value;
        throw new AssertionError("Missing request identity");
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESOURCE_EVENT", "PROCESS_EVENT", "TIME_ONCE"})
    void delayedNotificationResumesOriginalTaskAndLeavesOtherRequestsQueued(String kind)
            throws Exception {
        var seen = new CopyOnWriteArrayList<VetoRequest>();
        var resumed = new CountDownLatch(1);
        var service =
                serviceWith(
                        request -> {
                            seen.add(request);
                            if (seen.size() == 3) resumed.countDown();
                            return new VetoResponse(null, null, "done", null);
                        });
        try {
            service.submit("origin-wake", "Review apples", binding("System"), EPISODE_TIMEOUT);
            var agent = requireAgent(service.agent("origin-wake"));
            String firstId = requestIdentity(agent);
            service.submit("origin-wake", "Review oranges", binding("System"), EPISODE_TIMEOUT);
            String secondId = requestIdentity(agent);
            var old =
                    new MonitorRecord.Event(
                            "old",
                            "group",
                            Nullness.requireNonNull(kind),
                            "Apple report",
                            Instant.now(),
                            firstId,
                            "dispatch-old");
            var other =
                    new MonitorRecord.Event(
                            "other",
                            "group",
                            Nullness.requireNonNull(kind),
                            "Orange report",
                            Instant.now(),
                            secondId,
                            "dispatch-new");
            List<MonitorRecord.@NonNull Event> pending =
                    new CopyOnWriteArrayList<>(List.of(old, other));
            @NonNull MonitorService monitors = Mockito.mock();
            Mockito.when(monitors.pending(agent.id(), agent.sessionId().toString()))
                    .thenAnswer(call -> List.copyOf(pending));
            Mockito.doAnswer(
                            call -> {
                                pending.remove(call.getArgument(1));
                                return null;
                            })
                    .when(monitors)
                    .acknowledge(Mockito.eq(agent.id()), Mockito.any());
            agent.attachMonitor(monitors);
            agent.signalMonitor();
            assertTrue(resumed.await(5, TimeUnit.SECONDS));
            assertTrue(agent.await(EPISODE_TIMEOUT).success());
            assertEquals(List.of(other), List.copyOf(pending));
            var notification =
                    agent.history().stream()
                            .filter(t -> t.type() == TurnType.MONITOR_EVENT)
                            .toList()
                            .getLast();
            assertEquals(firstId, notification.payload().get("requestId"));
            String content = String.valueOf(notification.payload().get("content"));
            assertTrue(content.contains("Review apples"));
            assertFalse(content.contains("Review oranges"));
            assertTrue(
                    seen.getLast().messages().stream()
                            .anyMatch(m -> m.content().contains(content)));
        } finally {
            service.remove("origin-wake");
        }
    }

    @Test
    void notificationCannotCompleteNewlySubmittedRequestFuture() throws Exception {
        var calls = new AtomicInteger();
        var inNotification = new CountDownLatch(1);
        var releaseNotification = new CountDownLatch(1);
        var inUser = new CountDownLatch(1);
        var releaseUser = new CountDownLatch(1);
        var service =
                serviceWith(
                        request -> {
                            int call = calls.incrementAndGet();
                            try {
                                if (call == 2) {
                                    inNotification.countDown();
                                    assertTrue(releaseNotification.await(5, TimeUnit.SECONDS));
                                }
                                if (call == 3) {
                                    inUser.countDown();
                                    assertTrue(releaseUser.await(5, TimeUnit.SECONDS));
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                            return new VetoResponse(null, null, "reply-" + call, null);
                        });
        try {
            service.submit("future-wake", "Original", binding("System"), EPISODE_TIMEOUT);
            var agent = requireAgent(service.agent("future-wake"));
            var event =
                    new MonitorRecord.Event(
                            "late",
                            "group",
                            "RESOURCE_EVENT",
                            "Original report",
                            Instant.now(),
                            requestIdentity(agent),
                            "dispatch");
            var pending = new AtomicBoolean(true);
            @NonNull MonitorService monitors = Mockito.mock();
            Mockito.when(monitors.pending(agent.id(), agent.sessionId().toString()))
                    .thenAnswer(call -> pending.get() ? List.of(event) : List.of());
            Mockito.doAnswer(
                            call -> {
                                pending.set(false);
                                return null;
                            })
                    .when(monitors)
                    .acknowledge(agent.id(), event);
            agent.attachMonitor(monitors);
            agent.signalMonitor();
            assertTrue(inNotification.await(5, TimeUnit.SECONDS));
            agent.submit("New request");
            var newResult = agent.result();
            releaseNotification.countDown();
            assertTrue(inUser.await(5, TimeUnit.SECONDS));
            assertFalse(newResult.isDone(), "An old notification must not finish a new request");
            releaseUser.countDown();
            assertEquals("reply-3", newResult.get(5, TimeUnit.SECONDS).message());
        } finally {
            releaseNotification.countDown();
            releaseUser.countDown();
            service.remove("future-wake");
        }
    }

    @Test
    void exhaustedOriginalRequestCannotBorrowNewerRequestsBudget() throws Exception {
        var calls = new AtomicInteger();
        var service =
                serviceWith(
                        request -> {
                            if (calls.incrementAndGet() == 1)
                                throw new ModelSchemaException("Retry once");
                            return new VetoResponse(null, null, "done", null);
                        },
                        2L);
        try {
            service.submit("origin-budget", "Original task", binding("System"), EPISODE_TIMEOUT);
            var agent = requireAgent(service.agent("origin-budget"));
            String originalId = requestIdentity(agent);
            assertEquals(2, calls.get());
            service.submit("origin-budget", "Later task", binding("System"), EPISODE_TIMEOUT);
            assertEquals(3, calls.get());
            var event =
                    new MonitorRecord.Event(
                            "old-budget",
                            "group",
                            "RESOURCE_EVENT",
                            "Old report",
                            Instant.now(),
                            originalId,
                            "dispatch");
            @NonNull MonitorService monitors = Mockito.mock();
            Mockito.when(monitors.pending(agent.id(), agent.sessionId().toString()))
                    .thenReturn(List.of(event));
            var appended = new CountDownLatch(1);
            Mockito.doAnswer(
                            call -> {
                                appended.countDown();
                                return null;
                            })
                    .when(monitors)
                    .acknowledge(agent.id(), event);
            agent.attachMonitor(monitors);
            agent.signalMonitor();
            assertTrue(appended.await(5, TimeUnit.SECONDS));
            assertFalse(agent.await(EPISODE_TIMEOUT).success());
            assertEquals(3, calls.get(), "Old request already consumed both calls");
        } finally {
            service.remove("origin-budget");
        }
    }

    private static final Duration EPISODE_TIMEOUT = Duration.ofSeconds(10);

    /** Builds an {@link AgentService} wired with the default stubs + a capturing caller. */
    private static @NonNull AgentService serviceWith(@NonNull UniformLLMCaller caller) {
        return serviceWith(caller, 50L);
    }

    private static @NonNull AgentService serviceWith(
            @NonNull UniformLLMCaller caller, long maxCallsPerEpisode) {
        return serviceWith(caller, maxCallsPerEpisode, new TestToolEngine(), new HitlRegistry());
    }

    private static @NonNull AgentService serviceWith(
            @NonNull UniformLLMCaller caller,
            long maxCallsPerEpisode,
            @NonNull ToolEngine engine,
            @NonNull HitlRegistry hitl) {
        ObjectMapper mapper = new ObjectMapper();
        PromptCompiler compiler =
                new PromptCompiler(
                        new DefaultCapabilityTranslator(mapper),
                        new SystemPromptResolver(),
                        mapper,
                        "FULL_ACCESS");
        // Spring injects configuration in production; the unit test supplies explicit budgets.
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
                maxCallsPerEpisode,
                1000,
                "FULL_ACCESS",
                "STRICT",
                null,
                null,
                new BackgroundTaskManager(
                        new SandboxManager(TestSandboxFactory.uncontainedSubprocesses())));
    }

    @Test
    void continueAfterBreakerCarriesOriginalTaskWithoutChangingAuditedUserText() throws Exception {
        String originalTask = "Inspect the agent package and explain the remaining defect.";
        List<VetoRequest> seenRequests = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        UniformLLMCaller caller =
                request -> {
                    seenRequests.add(request);
                    if (calls.getAndIncrement() == 0) {
                        return new VetoResponse(
                                "I need to inspect one more thing.",
                                List.of(new ToolCall("missing_tool", Map.of(), "breaker-call")),
                                null,
                                null);
                    }
                    return new VetoResponse(
                            "The prior task context is available.",
                            null,
                            "Finished after resuming.",
                            null);
                };

        AgentService service = serviceWith(caller, 1L);
        @NonNull AgentWaitStore waits = Mockito.mock();
        Mockito.when(waits.load(Mockito.any(), Mockito.anyString())).thenReturn(Optional.empty());
        service.attachWaitStore(waits);
        AgentResult tripped =
                service.submit(
                        "breaker-continue",
                        originalTask,
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT);
        assertFalse(tripped.success(), "the first episode must trip at the one-call ceiling");
        Mockito.verify(waits)
                .save(
                        Mockito.any(),
                        Mockito.anyString(),
                        Mockito.argThat(
                                value ->
                                        value != null
                                                && value.reason()
                                                        == AgentWaitStore.Reason.BREAKER));
        assertEquals(Boolean.TRUE, tripped.metadata().get("breakerTrip"));

        AgentResult resumed =
                service.submit(
                        "breaker-continue",
                        "continue",
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT);

        assertTrue(resumed.success(), resumed.message());
        assertEquals(2, seenRequests.size(), "continue starts one fresh model call");
        VetoRequest resumeRequest = seenRequests.get(1);
        ChatMessage renderedResume =
                resumeRequest.messages().stream()
                        .filter(message -> "user".equals(message.role()))
                        .reduce((first, second) -> second)
                        .orElseThrow();
        assertTrue(
                renderedResume.content().contains("Continue the unfinished task"),
                renderedResume.content());
        assertTrue(renderedResume.content().contains(originalTask), renderedResume.content());

        VetoAgent agent = requireAgent(service.agent("breaker-continue"));
        TurnRecord auditedContinue =
                agent.history().stream()
                        .filter(turn -> turn.type() == TurnType.USER_PROMPT)
                        .reduce((first, second) -> second)
                        .orElseThrow();
        assertEquals("continue", auditedContinue.payload().get("content"));
        assertEquals(originalTask, auditedContinue.payload().get("resume_context"));
    }

    private static AgentRunner.@NonNull LlmBinding binding(@NonNull String systemPrompt) {
        return new AgentRunner.LlmBinding(
                ProviderType.DEEPSEEK,
                "stub-model",
                "stub-key",
                LlmOptions.defaults(),
                systemPrompt);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void malformedLocalArgumentsRejectTheWholeBatchBeforeApproval(boolean recover)
            throws Exception {
        @NonNull ToolEngine engine = Mockito.mock();
        var definition =
                new NativeToolDefinition(
                        "run_task",
                        "Start a task",
                        ToolCapability.PROCESS_EXECUTION,
                        Danger.DANGEROUS,
                        true,
                        ToolDocs.nonNullClass(RunTaskTool.class),
                        ToolDocs.nonNullClass(RunTaskTool.Args.class),
                        Map.of());
        Mockito.when(engine.getActiveTools(Mockito.any())).thenReturn(List.of(definition));
        Mockito.when(engine.resolveDefinition("run_task")).thenReturn(definition);
        var hitl = new HitlRegistry();
        AtomicInteger attempts = new AtomicInteger();
        var service =
                serviceWith(
                        request -> {
                            int attempt = attempts.incrementAndGet();
                            if (attempt > 1) {
                                String guidance = request.messages().getLast().content();
                                assertTrue(
                                        guidance.contains(
                                                "advertised argument schema for run_task"));
                                assertFalse(guidance.contains("malformed-secret-value"));
                                if (recover) return new VetoResponse(null, null, "Recovered", null);
                            }
                            return new VetoResponse(
                                    null,
                                    List.of(
                                            new ToolCall(
                                                    "run_task",
                                                    Map.of(
                                                            "commands",
                                                            List.of(
                                                                    Map.of(
                                                                            "executable",
                                                                            "java",
                                                                            "args",
                                                                            List.of("--version"))),
                                                            "timeout",
                                                            10),
                                                    "valid-first"),
                                            new ToolCall(
                                                    "run_task",
                                                    Map.of(
                                                            "commands",
                                                            "malformed-secret-value",
                                                            "timeout",
                                                            10),
                                                    "invalid-second")),
                                    null,
                                    null);
                        },
                        50,
                        engine,
                        hitl);
        String session = "invalid-batch-" + recover;
        try {
            var result =
                    service.submit(
                            session, "Validate this request", binding("System"), EPISODE_TIMEOUT);
            assertEquals(recover, result.success());
            assertEquals(recover ? 2 : 3, attempts.get());
            var agent = requireAgent(service.agent(session));
            assertTrue(hitl.pendingFor(agent.id()).isEmpty());
            assertTrue(
                    agent.history().stream().noneMatch(turn -> turn.type() == TurnType.TOOL_CALL));
            Mockito.verify(engine, Mockito.never()).execute(Mockito.any(), Mockito.any());
        } finally {
            service.remove(session);
        }
    }

    @Test
    void providerSchemaFailureUsesTheSameEphemeralRetryPath() throws Exception {
        List<VetoRequest> requests = new CopyOnWriteArrayList<>();
        UniformLLMCaller caller =
                request -> {
                    requests.add(request);
                    if (requests.size() == 1)
                        throw new ModelSchemaException("Malformed guide JSON");
                    assertTrue(
                            request.messages()
                                    .get(request.messages().size() - 1)
                                    .content()
                                    .contains("Malformed guide JSON"));
                    return new VetoResponse(null, null, "Recovered", null);
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
                                turn -> turn.payload().toString().contains("Malformed guide JSON")),
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
                request -> {
                    seenRequests.add(request);
                    if (seenRequests.size() == 1) {
                        return new VetoResponse(null, null, null, null);
                    }
                    return new VetoResponse(
                            "I'll answer directly.", null, "The answer is 4.", null);
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
        assertTrue(rejection.contains("regenerate"), "asks the model to regenerate");

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
                request -> {
                    seenRequests.add(request);
                    if (seenRequests.size() == 1) {
                        // thought present + stopping (no calls) + message missing → Rule 3 throws
                        // "message required (thought OFF or stopping)".
                        return new VetoResponse("thinking...", null, null, null);
                    }
                    return new VetoResponse(
                            "I'll answer directly.", null, "The answer is 4.", null);
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
                rejection.contains("message field is required"),
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
                request ->
                        new VetoResponse(
                                "I should answer directly.", null, "The answer is 4.", null);

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

    private static @NonNull VetoAgent requireAgent(VetoAgent agent) {
        if (agent == null) throw new AssertionError("expected agent");
        return agent;
    }
}
