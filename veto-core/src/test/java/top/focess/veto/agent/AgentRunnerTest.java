package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.identity.RoleToolFilter;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.llm.exceptions.ModelSchemaException;
import top.focess.veto.monitor.MonitorRecord;
import top.focess.veto.monitor.MonitorService;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.sandbox.SandboxManager;
import top.focess.veto.sandbox.TestSandboxFactory;

/**
 * Targets {@link AgentRunner}'s schema-violation retry path in isolation from the broader
 * end-to-end flows covered by {@code AgentEndToEndTest}. On a {@link ModelSchemaException} (thrown
 * by {@code ResponseEnforcer}) the runner must inject an ephemeral user-role rejection message into
 * the retry request — guiding the model to regenerate without persisting the rejection into turn
 * history.
 */
class AgentRunnerTest {

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

    private static final Duration EPISODE_TIMEOUT = Duration.ofSeconds(10);

    /** Builds an {@link AgentService} wired with the default stubs + a capturing caller. */
    private static @NonNull AgentService serviceWith(@NonNull UniformLLMCaller caller) {
        return serviceWith(caller, 50L);
    }

    private static @NonNull AgentService serviceWith(
            @NonNull UniformLLMCaller caller, long maxCallsPerEpisode) {
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
                new TestToolEngine(),
                new HitlRegistry(),
                new IngressDefense(),
                compiler,
                caller,
                mapper,
                List.of(),
                new RoleToolFilter(new TestToolEngine()),
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
        AgentResult tripped =
                service.submit(
                        "breaker-continue",
                        originalTask,
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT);
        assertFalse(tripped.success(), "the first episode must trip at the one-call ceiling");
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
