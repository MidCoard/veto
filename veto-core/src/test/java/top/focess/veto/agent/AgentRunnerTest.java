package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static top.focess.veto.integration.plugins.MonitorTestSupport.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.continuation.RequestContinuationEntity;
import top.focess.veto.agent.continuation.RequestContinuationRepository;
import top.focess.veto.agent.continuation.RequestContinuationStore;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefenseTestSupport;
import top.focess.veto.agent.loop.ContextBudgetConfiguration;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.AgentToolDefinition;
import top.focess.veto.agent.tool.NativeToolDefinition;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.agent.tool.ToolEngineImpl;
import top.focess.veto.agent.tool.builtin.FixtureLoopTool;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.workflow.PluginAwait;
import top.focess.veto.api.llm.ChatMessage;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.api.llm.exceptions.LlmException;
import top.focess.veto.api.llm.exceptions.ModelSchemaException;
import top.focess.veto.builtin.monitor.MonitorRecord;
import top.focess.veto.builtin.monitor.MonitorService;
import top.focess.veto.builtin.questions.QuestionRuntime;
import top.focess.veto.builtin.tools.AskUserTool;
import top.focess.veto.builtin.tools.RunTaskTool;
import top.focess.veto.bus.SessionInvalidations;
import top.focess.veto.event.EventManager;
import top.focess.veto.integration.plugins.QuestionTestSupport;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.llm.core.ToolResultPresenter;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.util.Nullness;
import top.focess.veto.vault.TestUsers;

/** Exercises tool waits, request ordering, cancellation, and durable agent continuation. */
class AgentRunnerTest {
    private static @NonNull ToolEngine questionEngine(@NonNull QuestionRuntime questions) {
        ApplicationContext spring = Mockito.mock(ApplicationContext.class);
        var tool = new AskUserTool(questions);
        Mockito.when(spring.getBeansOfType(AgentTool.class)).thenReturn(Map.of("askUser", tool));
        var engine =
                new ToolEngineImpl(
                        new ObjectMapper(),
                        List.of(),
                        spring,
                        Mockito.mock(SessionPlugins.class),
                        Mockito.mock(EventManager.class));
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
    void actualQuestionWaitRequiresDurableAnswerBeforeContinuing(@NonNull String action)
            throws Exception {
        var questions = new QuestionRuntime(QuestionTestSupport.host());
        AtomicInteger calls = new AtomicInteger();
        var dependencies = new AgentServiceTestSupport.Dependencies();
        if (action.equals("HISTORY_FAIL")) {
            TurnLogService turns = Mockito.mock(TurnLogService.class);
            Mockito.doThrow(new IllegalStateException("Answer log unavailable"))
                    .when(turns)
                    .logRequired(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString());
            dependencies.history(turns);
        }
        var service =
                serviceWith(
                        dependencies,
                        (request, modelSessionId) -> {
                            if (calls.getAndIncrement() == 0)
                                return new VetoResponse(
                                        "Need a format", List.of(questionCall()), null);
                            return new VetoResponse(null, null, "Handled");
                        },
                        5,
                        questionEngine(questions),
                        new HitlRegistry(null, Mockito.mock(SessionInvalidations.class)));

        var questionRequest =
                service.submitNow(
                        "question-wait", "Ask for a format", binding("System"), TestUsers.OWNER);
        var agent = requireAgent(service.agent("question-wait"));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (questions.pendingFor(QuestionTestSupport.scope(agent.id())).isEmpty()
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, questions.pendingFor(QuestionTestSupport.scope(agent.id())).size());
            assertEquals("QUESTION", agent.executionWaitReason());
            assertFalse(questionRequest.result().isDone());
            if (action.equals("INTERRUPT")) {
                assertTrue(agent.cancelTask(questionRequest, Duration.ofSeconds(5)));
                assertFalse(questionRequest.await(EPISODE_TIMEOUT).success());
                assertNull(agent.executionWaitReason());
                assertEquals(1, calls.get());
            } else {
                if (!action.equals("CANCEL"))
                    assertTrue(
                            questions.answer(
                                    QuestionTestSupport.scope(agent.id()),
                                    "question-call",
                                    Map.of("format", "Markdown")));
                else
                    assertTrue(
                            questions.cancel(
                                    QuestionTestSupport.scope(agent.id()), "question-call"));
                if (action.equals("HISTORY_FAIL")) {
                    assertFalse(questionRequest.await(EPISODE_TIMEOUT).success());
                    assertNull(agent.executionWaitReason());
                    assertEquals(1, calls.get());
                } else {
                    assertTrue(questionRequest.await(EPISODE_TIMEOUT).success());
                    assertNull(agent.executionWaitReason());
                    assertEquals(2, calls.get());
                }
            }
            assertTrue(questions.pendingFor(QuestionTestSupport.scope(agent.id())).isEmpty());
        } finally {
            service.remove("question-wait");
            assertTrue(agent.awaitTermination(Duration.ofSeconds(5)));
        }
    }

    @Test
    void queuedCancellationSettlesOnlyItsOwnRequest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var service =
                serviceWith(
                        (request, modelSessionId) -> {
                            if (calls.incrementAndGet() == 2) {
                                entered.countDown();
                                try {
                                    release.await(5, TimeUnit.SECONDS);
                                } catch (InterruptedException error) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            return new VetoResponse(null, null, "done");
                        });
        try {
            service.submit(
                    "owned-queue", "Warm up", binding("System"), EPISODE_TIMEOUT, TestUsers.OWNER);
            var agent = requireAgent(service.agent("owned-queue"));
            var active = agent.submitRequest("First");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var cancelled = agent.submitRequest("Second");
            var following = agent.submitRequest("Third");
            assertEquals(active.episode.id(), active.requestId());
            assertNotEquals(active.requestId(), cancelled.requestId());
            assertNotEquals(cancelled.requestId(), following.requestId());
            assertNotSame(active.result(), following.result());
            assertFalse(active.settled().isDone());
            var foreign = new RequestHandle(new Object());
            assertFalse(agent.cancelTask(foreign, Duration.ZERO));
            assertFalse(foreign.result().isDone());
            assertFalse(foreign.settled().isDone());
            assertTrue(agent.cancelTask(cancelled, Duration.ofSeconds(1)));
            assertFalse(cancelled.await(EPISODE_TIMEOUT).success());
            assertTrue(cancelled.settled().isDone());
            assertFalse(active.result().isDone());
            release.countDown();
            assertTrue(active.await(EPISODE_TIMEOUT).success());
            assertTrue(following.await(EPISODE_TIMEOUT).success());
            assertTrue(active.settled().get(5, TimeUnit.SECONDS));
            assertTrue(following.settled().get(5, TimeUnit.SECONDS));
            assertEquals(3, calls.get());
        } finally {
            release.countDown();
            service.remove("owned-queue");
        }
    }

    @Test
    void callbackFailureDoesNotStrandFollowingRequest() throws Exception {
        var service =
                serviceWith((request, modelSessionId) -> new VetoResponse(null, null, "done"));
        try {
            service.submit(
                    "callback-throw",
                    "Warm up",
                    binding("System"),
                    EPISODE_TIMEOUT,
                    TestUsers.OWNER);
            var agent = requireAgent(service.agent("callback-throw"));
            var first =
                    agent.submitRequest(
                            "First",
                            result -> {
                                throw new IllegalStateException("subscriber failed");
                            });
            var following = agent.submitRequest("Second");
            assertTrue(first.await(EPISODE_TIMEOUT).success());
            assertTrue(agent.cancelTask(first, Duration.ofSeconds(5)));
            assertTrue(following.await(EPISODE_TIMEOUT).success());
        } finally {
            service.remove("callback-throw");
        }
    }

    @Test
    void closingSettlesActiveAndQueuedRequests() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var service =
                serviceWith(
                        (request, modelSessionId) -> {
                            if (calls.incrementAndGet() == 2) {
                                entered.countDown();
                                try {
                                    release.await(5, TimeUnit.SECONDS);
                                } catch (InterruptedException error) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            return new VetoResponse(null, null, "done");
                        });
        try {
            service.submit(
                    "close-owned", "Warm up", binding("System"), EPISODE_TIMEOUT, TestUsers.OWNER);
            var agent = requireAgent(service.agent("close-owned"));
            var active = agent.submitRequest("First");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var queued = agent.submitRequest("Second");
            service.remove("close-owned");
            release.countDown();
            assertFalse(active.await(EPISODE_TIMEOUT).success());
            assertFalse(queued.await(EPISODE_TIMEOUT).success());
            assertTrue(active.settled().get(5, TimeUnit.SECONDS));
            assertTrue(queued.settled().get(5, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
            service.remove("close-owned");
        }
    }

    @Test
    void completedResultDoesNotConfirmTaskExitWhileCallbackStillRuns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var service =
                serviceWith((request, modelSessionId) -> new VetoResponse(null, null, "done"));
        try {
            service.submit(
                    "callback-exit",
                    "Warm up",
                    binding("System"),
                    EPISODE_TIMEOUT,
                    TestUsers.OWNER);
            var agent = requireAgent(service.agent("callback-exit"));
            var task =
                    agent.submitRequest(
                            "Next",
                            result -> {
                                entered.countDown();
                                try {
                                    release.await(5, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(task.result().isDone());
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

        UUID session = UUID.randomUUID();
        String agentId = UUID.randomUUID().toString();
        String oldRequest = "cancelled-request";
        RequestContinuationStore store = Mockito.mock(RequestContinuationStore.class);
        Mockito.when(store.load(session, agentId, oldRequest))
                .thenReturn(
                        Optional.of(
                                new RequestContinuationStore.Checkpoint("Old cancelled task", 1)));

        var service =
                serviceWith(
                        new AgentServiceTestSupport.Dependencies().continuations(store),
                        (request, modelSessionId) -> {
                            calls.incrementAndGet();
                            return new VetoResponse(null, null, "New task done");
                        });
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
                                    TestUsers.OWNER,
                                    "D:/IdeaProjects/veto/work/tmp/unfinished-group",
                                    0,
                                    ToolResultPresentationMode.BASIC);
            var event =
                    new MonitorRecord.Event(
                            "old-process-exit",
                            "process",
                            "PROCESS_EVENT",
                            "Original process exited",
                            Instant.now(),
                            oldRequest,
                            "process-instance");
            MonitorService monitors = Mockito.mock(MonitorService.class);
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
            agent.attachWorkSource(work(monitors));
            agent.signalWork();
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            assertEquals(0, calls.get());
            assertTrue(agent.submitRequest("A new task").await(EPISODE_TIMEOUT).success());
            assertEquals(1, calls.get());
            Mockito.verify(monitors, Mockito.never()).activationStarted(agentId, event);
            assertTrue(
                    agent.history().stream()
                            .noneMatch(
                                    turn ->
                                            turn.type() == TurnType.RUNTIME_EVENT
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
                        (request, modelSessionId) -> {
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
                                                                                "[Runtime"
                                                                                        + " cancellation]")));
                            }
                            return new VetoResponse(null, null, "completed");
                        });
        try {
            var first =
                    service.submitNow(
                            "cancel-task", "First task", binding("System"), TestUsers.OWNER);
            var agent = requireAgent(service.agent("cancel-task"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(agent.cancelTask(first, Duration.ofMillis(20)));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertFalse(agent.cancelTask(first, Duration.ofMillis(20)));
            assertEquals(
                    1,
                    interruptCount.get(),
                    "Repeated cancellation must not interrupt cleanup again");
            assertFalse(
                    first.result().isDone(),
                    "uncooperative execution must not be reported stopped");
            release.countDown();
            assertTrue(agent.cancelTask(first, Duration.ofSeconds(5)));
            assertFalse(first.await(EPISODE_TIMEOUT).success());
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
            MonitorService monitors = Mockito.mock(MonitorService.class);
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
            agent.attachWorkSource(work(monitors));
            agent.signalWork();
            assertTrue(observationCancelled.await(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get(), "Cancelled request must not resume on process exit");
            assertTrue(agent.submitRequest("Second task").await(EPISODE_TIMEOUT).success());
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
                        (request, modelSessionId) -> {
                            entered.countDown();
                            try {
                                new CountDownLatch(1).await();
                            } catch (InterruptedException error) {
                                throw new LlmException("provider call failed", error, false);
                            }
                            return new VetoResponse(null, null, "unexpected");
                        });
        try {
            var cancelled =
                    service.submitNow(
                            "wrapped-cancel", "Cancelled work", binding("System"), TestUsers.OWNER);
            var agent = requireAgent(service.agent("wrapped-cancel"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(agent.cancelTask(cancelled, Duration.ofSeconds(5)));
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
        var service =
                serviceWith((request, modelSessionId) -> new VetoResponse(null, null, "done"));
        try {
            service.submit(
                    "request-ids",
                    "First task",
                    binding("System"),
                    EPISODE_TIMEOUT,
                    TestUsers.OWNER);
            service.submit(
                    "request-ids",
                    "Second task",
                    binding("System"),
                    EPISODE_TIMEOUT,
                    TestUsers.OWNER);
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

    @ParameterizedTest
    @ValueSource(strings = {"RESOURCE_EVENT", "TIME_ONCE"})
    void directPromptWaitsForGroupMonitorCompletion(@NonNull String kind) throws Exception {
        var calls = new AtomicInteger();
        var parked = new CountDownLatch(1);
        var directDone = new CountDownLatch(1);
        var registered = new CountDownLatch(1);
        var groupReady = new CompletableFuture<Boolean>();
        var eventPending = new AtomicBoolean(false);
        var service =
                serviceWith(
                        (request, modelSessionId) -> {
                            int call = calls.incrementAndGet();
                            if (call == 2) {
                                parked.countDown();
                                try {
                                    registered.await(5, TimeUnit.SECONDS);
                                } catch (InterruptedException error) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            return new VetoResponse(null, null, "result-" + call);
                        });
        try {
            service.submit(
                    "direct-monitor",
                    "Initial task",
                    binding("System"),
                    EPISODE_TIMEOUT,
                    TestUsers.OWNER);
            var agent = requireAgent(service.agent("direct-monitor"));
            MonitorService monitors = Mockito.mock(MonitorService.class);
            var event =
                    new MonitorRecord.Event("done", "group", kind, "Group finished", Instant.now());
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
                                groupReady.complete(true);
                                return null;
                            })
                    .when(monitors)
                    .acknowledge(Mockito.eq(agent.id()), Mockito.any());
            agent.attachWorkSource(work(monitors));
            agent.addMessageListener(
                    message -> {
                        if (message.equals("result-4")) directDone.countDown();
                    });
            var workflow = agent.submitRequest("Wait for group");
            assertTrue(parked.await(5, TimeUnit.SECONDS));
            workflow.await(new PluginAwait("group-test", groupReady), agent::signalWork);
            registered.countDown();
            agent.submitUserPrompt("User follow-up");
            assertFalse(workflow.result().isDone());
            eventPending.set(true);
            agent.signalWork();
            assertEquals("result-3", workflow.await(EPISODE_TIMEOUT).message());
            assertTrue(directDone.await(5, TimeUnit.SECONDS));
        } finally {
            service.remove("direct-monitor");
        }
    }

    @Test
    void pluginWaitSettlementTracksTheResumedRequestUntilExecutionExits() throws Exception {
        var initialEntered = new CountDownLatch(1);
        var initialRelease = new CountDownLatch(1);
        var resumedEntered = new CountDownLatch(1);
        var resumedRelease = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var signal = new CompletableFuture<Boolean>();
        var pending = new AtomicBoolean();
        var service =
                serviceWith(
                        (modelRequest, modelSessionId) -> {
                            int call = calls.incrementAndGet();
                            if (call == 2) {
                                initialEntered.countDown();
                                try {
                                    if (!initialRelease.await(5, TimeUnit.SECONDS))
                                        throw new AssertionError("Initial model was not released");
                                } catch (InterruptedException error) {
                                    throw new AssertionError(error);
                                }
                            }
                            if (call == 3) {
                                resumedEntered.countDown();
                                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                                boolean released = false;
                                while (!released && System.nanoTime() < deadline) {
                                    try {
                                        released = resumedRelease.await(20, TimeUnit.MILLISECONDS);
                                    } catch (InterruptedException ignored) {
                                        // The provider keeps executing until explicitly released.
                                        interrupted.countDown();
                                    }
                                }
                                if (!released)
                                    throw new AssertionError("Resumed model was not released");
                            }
                            return new VetoResponse(null, null, "result-" + call);
                        });
        try {
            service.submit(
                    "wait-settlement",
                    "Initialize",
                    binding("System"),
                    EPISODE_TIMEOUT,
                    TestUsers.OWNER);
            var agent = requireAgent(service.agent("wait-settlement"));
            var request = agent.submitRequest("Wait for plugin work");
            assertTrue(initialEntered.await(5, TimeUnit.SECONDS));
            request.await(new PluginAwait("settlement/wait", signal), agent::signalWork);
            initialRelease.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (agent.state() != AgentState.WAITING && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertEquals(AgentState.WAITING, agent.state());
            assertFalse(request.result().isDone());
            assertFalse(request.settled().isDone());

            MonitorService monitors = Mockito.mock(MonitorService.class);
            var event =
                    new MonitorRecord.Event(
                            "settlement-event",
                            "group",
                            "RESOURCE_EVENT",
                            "Resume work",
                            Instant.now(),
                            request.requestId(),
                            "dispatch");
            Mockito.when(monitors.pending(agent.id(), agent.sessionId().toString()))
                    .thenAnswer(ignored -> pending.get() ? List.of(event) : List.of());
            Mockito.doAnswer(
                            ignored -> {
                                pending.set(false);
                                return null;
                            })
                    .when(monitors)
                    .acknowledge(Mockito.eq(agent.id()), Mockito.any());
            agent.attachWorkSource(work(monitors));
            pending.set(true);
            signal.complete(true);
            assertTrue(resumedEntered.await(5, TimeUnit.SECONDS));
            assertFalse(request.result().isDone());
            assertFalse(request.settled().isDone());
            assertFalse(agent.cancelTask(request, Duration.ofMillis(20)));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertFalse(request.result().isDone());
            assertFalse(request.settled().isDone());
            resumedRelease.countDown();
            assertTrue(agent.cancelTask(request, Duration.ofSeconds(5)));
            assertFalse(request.await(EPISODE_TIMEOUT).success());
            assertTrue(request.settled().get(5, TimeUnit.SECONDS));
            assertTrue(
                    agent.history().stream()
                            .anyMatch(
                                    turn ->
                                            turn.type() == TurnType.EXECUTION_ERROR
                                                    && "CANCELLED"
                                                            .equals(turn.payload().get("outcome"))
                                                    && request.requestId()
                                                            .equals(
                                                                    turn.payload()
                                                                            .get("requestId"))));
            var next = agent.submitRequest("Independent next task");
            assertNotEquals(request.requestId(), next.requestId());
            assertTrue(next.await(EPISODE_TIMEOUT).success());
            assertTrue(next.settled().get(5, TimeUnit.SECONDS));
            assertEquals(4, calls.get());
        } finally {
            initialRelease.countDown();
            resumedRelease.countDown();
            service.remove("wait-settlement");
        }
    }

    @Test
    void failedWaitDuringModelCallBlocksTheNextToolEffect() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var signal = new CompletableFuture<Boolean>();
        var definition =
                AgentToolDefinition.from(
                        "fixture_loop",
                        FixtureLoopTool.class,
                        FixtureLoopTool.Args.class,
                        ToolCapability.LOOP_CONTROL);
        ToolEngine engine = Mockito.mock(ToolEngine.class);
        Mockito.when(engine.getActiveTools(Mockito.any())).thenReturn(List.of(definition));
        Mockito.when(engine.resolveDefinition("fixture_loop")).thenReturn(definition);
        var service =
                serviceWith(
                        (request, modelSessionId) -> {
                            if (calls.incrementAndGet() == 1)
                                return new VetoResponse(null, null, "initialized");
                            entered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("Timed out");
                            } catch (InterruptedException error) {
                                throw new AssertionError(error);
                            }
                            return new VetoResponse(
                                    null, List.of(new ToolCall("fixture_loop", Map.of())), null);
                        },
                        50,
                        engine,
                        new HitlRegistry(null, Mockito.mock(SessionInvalidations.class)));
        try {
            service.submit(
                    "active-wait-failure",
                    "Initialize",
                    binding("System"),
                    EPISODE_TIMEOUT,
                    TestUsers.OWNER);
            var agent = requireAgent(service.agent("active-wait-failure"));
            var request = agent.submitRequest("Work");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            request.await(new PluginAwait("generation/wait", signal), agent::signalWork);
            signal.completeExceptionally(new IllegalStateException("Plugin stopped"));
            release.countDown();
            assertFalse(request.await(EPISODE_TIMEOUT).success());
            assertTrue(request.settled().get(5, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
            Mockito.verify(engine, Mockito.never()).execute(Mockito.any(), Mockito.any());
        } finally {
            release.countDown();
            service.remove("active-wait-failure");
        }
    }

    @Test
    void failedWaitRejectsPendingObservationBeforeCallingModel() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var signal = new CompletableFuture<Boolean>();
        var service =
                serviceWith(
                        (request, modelSessionId) -> {
                            if (calls.incrementAndGet() == 2) {
                                entered.countDown();
                                try {
                                    release.await(5, TimeUnit.SECONDS);
                                } catch (InterruptedException error) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                            return new VetoResponse(null, null, "waiting");
                        });
        try {
            service.submit(
                    "wait-failure",
                    "Initialize",
                    binding("System"),
                    EPISODE_TIMEOUT,
                    TestUsers.OWNER);
            var agent = requireAgent(service.agent("wait-failure"));
            var request = agent.submitRequest("Wait");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            request.await(new PluginAwait("generation/wait", signal), agent::signalWork);
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (agent.state() != AgentState.WAITING && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertEquals(AgentState.WAITING, agent.state());
            MonitorService monitors = Mockito.mock(MonitorService.class);
            Mockito.when(monitors.pending(agent.id(), agent.sessionId().toString()))
                    .thenReturn(
                            List.of(
                                    new MonitorRecord.Event(
                                            "other",
                                            "group",
                                            "RESOURCE_EVENT",
                                            "Work",
                                            Instant.now(),
                                            request.requestId(),
                                            "dispatch")));
            agent.attachWorkSource(work(monitors));
            signal.completeExceptionally(new IllegalStateException("Plugin stopped"));
            assertFalse(request.await(EPISODE_TIMEOUT).success());
            assertTrue(request.settled().get(5, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
            Mockito.verify(monitors, Mockito.never())
                    .acknowledge(Mockito.anyString(), Mockito.any());
        } finally {
            release.countDown();
            service.remove("wait-failure");
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
                        (request, modelSessionId) -> {
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
                            return new VetoResponse(null, null, "result-" + call);
                        });
        try {
            var workflow =
                    service.submitNow(
                            "direct-user", "Group task", binding("System"), TestUsers.OWNER);
            var agent = requireAgent(service.agent("direct-user"));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            agent.submitUserPrompt("User follow-up");
            assertFalse(workflow.result().isDone());
            releaseFirst.countDown();
            assertEquals("result-1", workflow.await(EPISODE_TIMEOUT).message());
            assertTrue(directEntered.await(5, TimeUnit.SECONDS));
            var nextWorkflow =
                    agent.submitRequest(
                            "Next group task",
                            result -> {
                                callbacks.incrementAndGet();
                                thirdDone.countDown();
                            });
            releaseDirect.countDown();
            assertTrue(thirdDone.await(5, TimeUnit.SECONDS));
            assertEquals("result-3", nextWorkflow.await(EPISODE_TIMEOUT).message());
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
                        (request, modelSessionId) -> {
                            seen.add(request);
                            if (seen.size() > 1) resumed.countDown();
                            return new VetoResponse(null, null, "Done");
                        });
        service.submit(
                "monitor-wake",
                "Initial task",
                binding("System"),
                EPISODE_TIMEOUT,
                TestUsers.OWNER);
        var agent = requireAgent(service.agent("monitor-wake"));
        MonitorService monitors = Mockito.mock(MonitorService.class);
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
        var completion = completion(monitors, agent.id(), event);
        agent.attachWorkSource(work(monitors));
        try {
            agent.signalWork();
            if (retryAcknowledgement) {
                assertTrue(acknowledgementFailed.await(5, TimeUnit.SECONDS));
                awaitCondition(
                        () ->
                                agent.history().stream()
                                        .anyMatch(turn -> turn.type() == TurnType.EXECUTION_ERROR));
                assertEquals(1, seen.size(), "Failed acknowledgement must not run the model");
                agent.signalWork();
            }
            assertTrue(resumed.await(5, TimeUnit.SECONDS));
            assertEquals(Boolean.TRUE, completion.get(5, TimeUnit.SECONDS));
            assertEquals(
                    1,
                    agent.history().stream().filter(t -> t.type() == TurnType.USER_PROMPT).count());
            assertEquals(
                    1,
                    agent.history().stream()
                            .filter(t -> t.type() == TurnType.RUNTIME_EVENT)
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
                        (request, modelSessionId) -> {
                            calls.incrementAndGet();
                            return new VetoResponse(null, null, "Done");
                        },
                        1L);
        service.submit(
                "monitor-budget",
                "Initial task",
                binding("System"),
                EPISODE_TIMEOUT,
                TestUsers.OWNER);
        var agent = requireAgent(service.agent("monitor-budget"));
        MonitorService monitors = Mockito.mock(MonitorService.class);
        var event =
                new MonitorRecord.Event(
                        "result",
                        "group",
                        "RESOURCE_EVENT",
                        "Task completed",
                        Instant.now(),
                        requestIdentity(agent),
                        "dispatch");
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
        var completion = completion(monitors, agent.id(), event);
        agent.attachWorkSource(work(monitors));
        try {
            agent.signalWork();
            assertTrue(consumed.await(5, TimeUnit.SECONDS));
            assertEquals(Boolean.FALSE, completion.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally {
            agent.terminate();
        }
    }

    static @NonNull String requestIdentity(@NonNull VetoAgent agent) {
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
                        (request, modelSessionId) -> {
                            seen.add(request);
                            if (seen.size() == 3) resumed.countDown();
                            return new VetoResponse(null, null, "done");
                        });
        try {
            service.submit(
                    "origin-wake",
                    "Review apples",
                    binding("System"),
                    EPISODE_TIMEOUT,
                    TestUsers.OWNER);
            var agent = requireAgent(service.agent("origin-wake"));
            String firstId = requestIdentity(agent);
            service.submit(
                    "origin-wake",
                    "Review oranges",
                    binding("System"),
                    EPISODE_TIMEOUT,
                    TestUsers.OWNER);
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
            MonitorService monitors = Mockito.mock(MonitorService.class);
            Mockito.when(monitors.pending(agent.id(), agent.sessionId().toString()))
                    .thenAnswer(call -> List.copyOf(pending));
            Mockito.doAnswer(
                            call -> {
                                pending.remove(call.getArgument(1));
                                return null;
                            })
                    .when(monitors)
                    .acknowledge(Mockito.eq(agent.id()), Mockito.any());
            var completion = completion(monitors, agent.id(), old);
            agent.attachWorkSource(work(monitors));
            agent.signalWork();
            assertTrue(resumed.await(5, TimeUnit.SECONDS));
            assertEquals(Boolean.TRUE, completion.get(5, TimeUnit.SECONDS));
            assertEquals(List.of(other), List.copyOf(pending));
            var notification =
                    agent.history().stream()
                            .filter(t -> t.type() == TurnType.RUNTIME_EVENT)
                            .toList()
                            .getLast();
            assertEquals(firstId, notification.payload().get("requestId"));
            String content = String.valueOf(notification.payload().get("compiled_observation"));
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
                        (request, modelSessionId) -> {
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
                            return new VetoResponse(null, null, "reply-" + call);
                        });
        try {
            service.submit(
                    "future-wake", "Original", binding("System"), EPISODE_TIMEOUT, TestUsers.OWNER);
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
            MonitorService monitors = Mockito.mock(MonitorService.class);
            Mockito.when(monitors.pending(agent.id(), agent.sessionId().toString()))
                    .thenAnswer(call -> pending.get() ? List.of(event) : List.of());
            Mockito.doAnswer(
                            call -> {
                                pending.set(false);
                                return null;
                            })
                    .when(monitors)
                    .acknowledge(agent.id(), event);
            agent.attachWorkSource(work(monitors));
            agent.signalWork();
            assertTrue(inNotification.await(5, TimeUnit.SECONDS));
            var newResult = agent.submitRequest("New request").result();
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
                        (request, modelSessionId) -> {
                            if (calls.incrementAndGet() == 1)
                                throw new ModelSchemaException("Retry once");
                            return new VetoResponse(null, null, "done");
                        },
                        2L);
        try {
            service.submit(
                    "origin-budget",
                    "Original task",
                    binding("System"),
                    EPISODE_TIMEOUT,
                    TestUsers.OWNER);
            var agent = requireAgent(service.agent("origin-budget"));
            String originalId = requestIdentity(agent);
            assertEquals(2, calls.get());
            service.submit(
                    "origin-budget",
                    "Later task",
                    binding("System"),
                    EPISODE_TIMEOUT,
                    TestUsers.OWNER);
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
            MonitorService monitors = Mockito.mock(MonitorService.class);
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
            var completion = completion(monitors, agent.id(), event);
            agent.attachWorkSource(work(monitors));
            agent.signalWork();
            assertTrue(appended.await(5, TimeUnit.SECONDS));
            assertEquals(Boolean.FALSE, completion.get(5, TimeUnit.SECONDS));
            assertEquals(3, calls.get(), "Old request already consumed both calls");
        } finally {
            service.remove("origin-budget");
        }
    }

    static void awaitCondition(@NonNull BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + EPISODE_TIMEOUT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Expected observation was not completed");
    }

    static final @NonNull Duration EPISODE_TIMEOUT = Duration.ofSeconds(10);

    /** Builds an {@link AgentService} wired with the default stubs + a capturing caller. */
    static @NonNull AgentService serviceWith(@NonNull UniformLLMCaller caller) {
        return serviceWith(new AgentServiceTestSupport.Dependencies(), caller);
    }

    static @NonNull AgentService serviceWith(
            AgentServiceTestSupport.@NonNull Dependencies dependencies,
            @NonNull UniformLLMCaller caller) {
        return serviceWith(dependencies, caller, 50L);
    }

    static @NonNull AgentService serviceWith(
            @NonNull UniformLLMCaller caller, long maxCallsPerEpisode) {
        return serviceWith(new AgentServiceTestSupport.Dependencies(), caller, maxCallsPerEpisode);
    }

    static @NonNull AgentService serviceWith(
            AgentServiceTestSupport.@NonNull Dependencies dependencies,
            @NonNull UniformLLMCaller caller,
            long maxCallsPerEpisode) {
        return serviceWith(
                dependencies,
                caller,
                maxCallsPerEpisode,
                new TestToolEngine(),
                new HitlRegistry(null, Mockito.mock(SessionInvalidations.class)));
    }

    static @NonNull AgentService serviceWith(
            @NonNull UniformLLMCaller caller,
            long maxCallsPerEpisode,
            @NonNull ToolEngine engine,
            @NonNull HitlRegistry hitl) {
        return serviceWith(
                new AgentServiceTestSupport.Dependencies(),
                caller,
                maxCallsPerEpisode,
                engine,
                hitl);
    }

    static @NonNull AgentService serviceWith(
            AgentServiceTestSupport.@NonNull Dependencies dependencies,
            @NonNull UniformLLMCaller caller,
            long maxCallsPerEpisode,
            @NonNull ToolEngine engine,
            @NonNull HitlRegistry hitl) {
        ObjectMapper mapper = new ObjectMapper();
        PromptCompiler compiler =
                new PromptCompiler(
                        new VetoCapabilityTranslator(),
                        new SystemPromptResolver(),
                        mapper,
                        new ToolResultPresenter(mapper),
                        "FULL_ACCESS",
                        new ContextBudgetConfiguration(),
                        32000,
                        0.9);
        return AgentServiceTestSupport.create(
                dependencies,
                engine,
                hitl,
                IngressDefenseTestSupport.inMemory(),
                compiler,
                caller,
                mapper,
                List.of(),
                "REAL",
                maxCallsPerEpisode,
                "FULL_ACCESS",
                "STRICT",
                null,
                null);
    }

    @Test
    void historySeededContinueRemainsANewTaskWithoutGuessingBreakerWait() throws Exception {
        UUID session = UUID.randomUUID();
        String oldRequest = UUID.randomUUID().toString();
        String agentId = UUID.randomUUID().toString();
        RequestContinuationStore store = Mockito.mock(RequestContinuationStore.class);
        var calls = new AtomicInteger();

        var service =
                serviceWith(
                        new AgentServiceTestSupport.Dependencies().continuations(store),
                        (request, modelSessionId) -> {
                            calls.incrementAndGet();
                            return new VetoResponse(null, null, "done");
                        },
                        1L);
        var history =
                List.of(
                        new TurnRecord(
                                1,
                                TurnType.USER_PROMPT,
                                Map.of("content", "Unfinished old task", "requestId", oldRequest),
                                Instant.now()));
        try {
            var agent =
                    (VetoAgent)
                            service.getOrCreateAgent(
                                    session.toString(),
                                    agentId,
                                    binding("System"),
                                    history,
                                    TestUsers.OWNER,
                                    "D:/IdeaProjects/veto/work/tmp/breaker-budget",
                                    0,
                                    ToolResultPresentationMode.BASIC);
            assertTrue(
                    service.submit(
                                    session.toString(),
                                    "continue",
                                    binding("System"),
                                    EPISODE_TIMEOUT,
                                    TestUsers.OWNER)
                            .success());
            assertEquals(1, calls.get());
            String newRequest = requestIdentity(agent);
            assertNotEquals(oldRequest, newRequest);
            Mockito.verify(store).save(session, agentId, newRequest, "continue", 0, 1L);
            Mockito.verify(store).save(session, agentId, newRequest, "continue", 1, 1L);
            var last =
                    agent.history().stream()
                            .filter(turn -> turn.type() == TurnType.USER_PROMPT)
                            .reduce((first, second) -> second)
                            .orElseThrow();
            assertEquals("continue", last.payload().get("content"));
            assertFalse(last.payload().containsKey("resume_context"));
            assertEquals(history.getFirst(), agent.history().getFirst());
        } finally {
            service.remove(session.toString());
        }
    }

    @Test
    void repeatedBreakerContinuePersistsCumulativeBudgetBeforeEachModelCall() throws Exception {
        RequestContinuationRepository repository =
                Mockito.mock(RequestContinuationRepository.class);
        Map<String, RequestContinuationEntity> durable = new ConcurrentHashMap<>();
        Mockito.when(repository.findById(Mockito.anyString()))
                .thenAnswer(
                        invocation -> Optional.ofNullable(durable.get(invocation.getArgument(0))));
        Mockito.when(repository.saveAndFlush(Mockito.any()))
                .thenAnswer(
                        invocation -> {
                            RequestContinuationEntity row = invocation.getArgument(0);
                            if (row == null) throw new AssertionError("Missing checkpoint");
                            durable.put(row.getId(), row);
                            return row;
                        });
        var store = new RequestContinuationStore(repository);
        var calls = new AtomicInteger();

        var service =
                serviceWith(
                        new AgentServiceTestSupport.Dependencies().continuations(store),
                        (request, modelSessionId) -> {
                            int current = calls.incrementAndGet();
                            assertEquals(
                                    1,
                                    durable.size(),
                                    "Continue retains the same request checkpoint");
                            var checkpoint = durable.values().iterator().next();
                            assertEquals(current, checkpoint.getConsumedCalls());
                            assertEquals(Long.valueOf(current), checkpoint.getGrantedCalls());
                            assertEquals("Original task", checkpoint.getTask());
                            return current < 3
                                    ? new VetoResponse(
                                            null,
                                            List.of(
                                                    new ToolCall(
                                                            "missing_tool",
                                                            Map.of(),
                                                            "call-" + current)),
                                            null)
                                    : new VetoResponse(null, null, "Done");
                        },
                        1L);
        String session = UUID.randomUUID().toString();
        try {
            assertFalse(
                    service.submit(
                                    session,
                                    "Original task",
                                    binding("System"),
                                    EPISODE_TIMEOUT,
                                    TestUsers.OWNER)
                            .success());
            assertFalse(
                    service.submit(
                                    session,
                                    "continue",
                                    binding("System"),
                                    EPISODE_TIMEOUT,
                                    TestUsers.OWNER)
                            .success());
            assertTrue(
                    service.submit(
                                    session,
                                    "continue",
                                    binding("System"),
                                    EPISODE_TIMEOUT,
                                    TestUsers.OWNER)
                            .success());
            assertEquals(3, calls.get());
            var agent = requireAgent(service.agent(session));
            var checkpoint =
                    new RequestContinuationStore(repository)
                            .load(agent.sessionId(), agent.id(), requestIdentity(agent))
                            .orElseThrow();
            assertEquals(3, checkpoint.consumedCalls());
            assertEquals(Long.valueOf(3), checkpoint.grantedCalls());
            var prompts =
                    agent.history().stream()
                            .filter(turn -> turn.type() == TurnType.USER_PROMPT)
                            .toList();
            assertEquals(
                    List.of("Original task", "continue", "continue"),
                    prompts.stream().map(turn -> turn.payload().get("content")).toList());
            assertEquals("Original task", prompts.get(1).payload().get("resume_context"));
            assertEquals("Original task", prompts.get(2).payload().get("resume_context"));
        } finally {
            service.remove(session);
        }
    }

    @Test
    void continueAfterBreakerCarriesOriginalTaskWithoutChangingAuditedUserText() throws Exception {
        String originalTask = "Inspect the agent package and explain the remaining defect.";
        List<VetoRequest> seenRequests = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        UniformLLMCaller caller =
                (request, modelSessionId) -> {
                    seenRequests.add(request);
                    if (calls.getAndIncrement() == 0) {
                        return new VetoResponse(
                                "I need to inspect one more thing.",
                                List.of(new ToolCall("missing_tool", Map.of(), "breaker-call")),
                                null);
                    }
                    return new VetoResponse(
                            "The prior task context is available.",
                            null,
                            "Finished after resuming.");
                };

        AgentService service = serviceWith(caller, 1L);
        AgentResult tripped =
                service.submit(
                        "breaker-continue",
                        originalTask,
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT,
                        TestUsers.OWNER);
        assertFalse(tripped.success(), "the first episode must trip at the one-call ceiling");
        assertEquals(Boolean.TRUE, tripped.metadata().get("breakerTrip"));

        AgentResult resumed =
                service.submit(
                        "breaker-continue",
                        "continue",
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT,
                        TestUsers.OWNER);

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

    static @NonNull LlmBinding binding(@NonNull String ignoredPrompt) {
        return new LlmBinding(
                ProviderType.DEEPSEEK, "stub-model", "stub-key", LlmOptions.defaults(), null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void malformedLocalArgumentsRejectTheWholeBatchBeforeApproval(boolean recover)
            throws Exception {
        ToolEngine engine = Mockito.mock(ToolEngine.class);
        var definition =
                new NativeToolDefinition(
                        "run_task",
                        "Start a task",
                        ToolCapability.PROCESS_EXECUTION,
                        Danger.DANGEROUS,
                        true,
                        RunTaskTool.class,
                        RunTaskTool.Args.class,
                        Map.of());
        Mockito.when(engine.getActiveTools(Mockito.any())).thenReturn(List.of(definition));
        Mockito.when(engine.resolveDefinition("run_task")).thenReturn(definition);
        var hitl = new HitlRegistry(null, Mockito.mock(SessionInvalidations.class));
        AtomicInteger attempts = new AtomicInteger();
        var service =
                serviceWith(
                        (request, modelSessionId) -> {
                            int attempt = attempts.incrementAndGet();
                            if (attempt > 1) {
                                String guidance = request.messages().getLast().content();
                                assertTrue(
                                        guidance.contains(
                                                "advertised argument schema for run_task"));
                                assertTrue(guidance.contains("parameter 'commands'"));
                                assertTrue(guidance.contains("got string"));
                                assertFalse(guidance.contains("malformed-secret-value"));
                                if (recover) return new VetoResponse(null, null, "Recovered");
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
                                    null);
                        },
                        50,
                        engine,
                        hitl);
        String session = "invalid-batch-" + recover;
        try {
            var result =
                    service.submit(
                            session,
                            "Validate this request",
                            binding("System"),
                            EPISODE_TIMEOUT,
                            TestUsers.OWNER);
            assertEquals(recover, result.success());
            assertEquals(recover ? 2 : 50, attempts.get());
            var agent = requireAgent(service.agent(session));
            assertTrue(hitl.pendingFor(agent.id()).isEmpty());
            assertTrue(
                    agent.history().stream().noneMatch(turn -> turn.type() == TurnType.TOOL_CALL));
            Mockito.verify(engine, Mockito.never()).execute(Mockito.any(), Mockito.any());
        } finally {
            service.remove(session);
        }
    }

    static @NonNull VetoAgent requireAgent(VetoAgent agent) {
        if (agent == null) throw new AssertionError("expected agent");
        return agent;
    }
}
