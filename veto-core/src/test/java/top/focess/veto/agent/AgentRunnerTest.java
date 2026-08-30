package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.mcp.DefaultToolEngine;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.core.VetoResponse;

/**
 * Targets {@link AgentRunner}'s schema-violation retry path in isolation from the broader
 * end-to-end flows covered by {@code AgentEndToEndTest}. On a {@link
 * top.focess.veto.llm.exceptions.ModelSchemaException} (thrown by {@code ResponseEnforcer}) the
 * runner must inject an ephemeral user-role rejection message into the retry request — guiding the
 * model to regenerate without persisting the rejection into turn history.
 */
class AgentRunnerTest {

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
                        mapper);
        // The @Value defaults are only injected by Spring; set sensible budgets for the unit test.
        ReflectionTestUtils.setField(compiler, "maxInputTokens", 32000);
        ReflectionTestUtils.setField(compiler, "contextFillRatio", 0.9);
        return new AgentService(
                new DefaultToolEngine(),
                new HitlRegistry(),
                new IngressDefense(),
                compiler,
                caller,
                mapper,
                List.of(),
                new top.focess.veto.agent.identity.RoleToolFilter(new DefaultToolEngine()),
                "REAL",
                maxCallsPerEpisode,
                "FULL_ACCESS",
                "STRICT",
                null,
                null,
                new top.focess.veto.sandbox.BackgroundTaskManager(
                        new top.focess.veto.sandbox.SandboxManager(
                                top.focess.veto.sandbox.TestSandboxFactory
                                        .uncontainedSubprocesses())));
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
                                List.of(
                                        new top.focess.veto.llm.core.ToolCall(
                                                "missing_tool", Map.of(), "breaker-call")),
                                null,
                                new VetoResponse.Features(false),
                                null);
                    }
                    return new VetoResponse(
                            "The prior task context is available.",
                            List.of(),
                            "Finished after resuming.",
                            new VetoResponse.Features(false),
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
    void guidedRequestSwitchesTheFollowingCallToTheActionsSchema() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<VetoRequest> seenRequests = new CopyOnWriteArrayList<>();
        var guidedActions =
                mapper.readTree(
                        """
                        [
                          {
                            "id": "capture",
                            "label": "Capture the observation",
                            "type": "tool",
                            "tool": "missing_tool",
                            "inputs": {},
                            "outputs": {"result": "content"}
                          },
                          {
                            "id": "finish",
                            "label": "Return the result",
                            "type": "STOP",
                            "result_binding": "result"
                          }
                        ]
                        """);
        UniformLLMCaller caller =
                request -> {
                    seenRequests.add(request);
                    if (seenRequests.size() == 1) {
                        return new VetoResponse(
                                "I will switch to a deterministic program after this call.",
                                List.of(
                                        new top.focess.veto.llm.core.ToolCall(
                                                "missing_tool", Map.of(), "switch-call")),
                                null,
                                new VetoResponse.Features(true),
                                null);
                    }
                    return new VetoResponse(
                            null,
                            List.of(),
                            null,
                            new VetoResponse.Features(true),
                            guidedActions);
                };

        AgentResult result =
                serviceWith(caller)
                        .submit(
                                "guided-schema-switch",
                                "Use a guided program.",
                                binding("You are a helpful assistant."),
                                EPISODE_TIMEOUT);

        assertTrue(result.success(), result.message());
        assertEquals(2, seenRequests.size(), "guided authoring needs a second model iteration");
        var autonomousSchema = seenRequests.get(0).responseSchema();
        if (autonomousSchema == null) {
            throw new AssertionError("autonomous response schema missing");
        }
        var autonomousProperties = autonomousSchema.get("properties");
        assertTrue(autonomousProperties.has("calls"));
        assertFalse(autonomousProperties.has("actions"));
        var guidedSchema = seenRequests.get(1).responseSchema();
        if (guidedSchema == null) {
            throw new AssertionError("guided response schema missing");
        }
        var guidedProperties = guidedSchema.get("properties");
        assertFalse(guidedProperties.has("calls"));
        assertTrue(guidedProperties.has("actions"));
    }

    @Test
    void schemaViolationInjectsEphemeralRejectionMessageThenRetries() throws Exception {
        // A capturing caller: the first call returns a schema-violating response (features absent
        // so ResponseEnforcer throws ModelSchemaException); the retry returns a valid stopping
        // response (no tool calls).
        List<VetoRequest> seenRequests = new CopyOnWriteArrayList<>();
        UniformLLMCaller caller =
                request -> {
                    seenRequests.add(request);
                    if (seenRequests.size() == 1) {
                        return new VetoResponse(null, List.of(), null, null, null);
                    }
                    return new VetoResponse(
                            "I'll answer directly.",
                            List.of(),
                            "The answer is 4.",
                            new VetoResponse.Features(false),
                            null);
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
        assertTrue(rejection.contains("features is required"), "echoes the violation detail");
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
                        return new VetoResponse(
                                "thinking...",
                                List.of(),
                                null,
                                new VetoResponse.Features(false),
                                null);
                    }
                    return new VetoResponse(
                            "I'll answer directly.",
                            List.of(),
                            "The answer is 4.",
                            new VetoResponse.Features(false),
                            null);
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
                                "I should answer directly.",
                                List.of(),
                                "The answer is 4.",
                                new VetoResponse.Features(false),
                                null);

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
