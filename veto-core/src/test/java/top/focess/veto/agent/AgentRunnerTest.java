package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.mcp.DefaultMcpEngine;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
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
    private static AgentService serviceWith(UniformLLMCaller caller) {
        ObjectMapper mapper = new ObjectMapper();
        PromptCompiler compiler =
                new PromptCompiler(
                        new DefaultCapabilityTranslator(mapper),
                        Workspace.single(
                                Path.of(System.getProperty("user.dir", ".")), PathMode.REAL));
        // The @Value defaults are only injected by Spring; set sensible budgets for the unit test.
        ReflectionTestUtils.setField(compiler, "maxInputTokens", 32000);
        ReflectionTestUtils.setField(compiler, "contextFillRatio", 0.9);
        return new AgentService(
                new DefaultMcpEngine(),
                new HitlRegistry(),
                new IngressDefense(),
                compiler,
                caller,
                mapper,
                List.of(),
                System.getProperty("user.dir", "."),
                "",
                "REAL",
                50L,
                "FULL_ACCESS",
                "STRICT",
                null,
                null);
    }

    private static AgentRunner.LlmBinding binding(String systemPrompt) {
        return new AgentRunner.LlmBinding(
                ProviderType.DEEPSEEK,
                "stub-model",
                "stub-key",
                LlmOptions.defaults(),
                systemPrompt);
    }

    @Test
    void schemaViolationInjectsEphemeralRejectionMessageThenRetries() throws Exception {
        // A capturing caller: the first call returns a schema-violating response (features absent
        // so ResponseEnforcer throws ModelSchemaException); the retry returns a valid finished one.
        List<VetoRequest> seenRequests = new CopyOnWriteArrayList<>();
        UniformLLMCaller caller =
                request -> {
                    seenRequests.add(request);
                    if (seenRequests.size() == 1) {
                        return new VetoResponse(null, List.of(), null, false, null, null);
                    }
                    return new VetoResponse(
                            "I'll answer directly.",
                            List.of(),
                            "The answer is 4.",
                            true,
                            new VetoResponse.Features(false, true),
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
        VetoAgent agent = service.agent("schema-retry");
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
     * Regression for the getExpectedDescription substring-collision bug: the message-required
     * exception ("message required (thought OFF or is_finished)") contains the substring "thought
     * OFF", so the rejection guidance must map it to the message-required description — not the
     * thought-OFF description. Driven by a response that is finished with thought present but no
     * message (triggers ResponseEnforcer Rule 3).
     */
    @Test
    void messageRequiredViolationMapsToMessageDescriptionNotThoughtOff() throws Exception {
        List<VetoRequest> seenRequests = new CopyOnWriteArrayList<>();
        UniformLLMCaller caller =
                request -> {
                    seenRequests.add(request);
                    if (seenRequests.size() == 1) {
                        // thought present + finished + message missing → Rule 3 throws
                        // "message required (thought OFF or is_finished)".
                        return new VetoResponse(
                                "thinking...",
                                List.of(),
                                null,
                                true,
                                new VetoResponse.Features(false, true),
                                null);
                    }
                    return new VetoResponse(
                            "I'll answer directly.",
                            List.of(),
                            "The answer is 4.",
                            true,
                            new VetoResponse.Features(false, true),
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
}
