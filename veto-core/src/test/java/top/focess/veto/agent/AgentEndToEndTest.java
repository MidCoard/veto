package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.mcp.DefaultMcpEngine;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoResponse;

/**
 * Exercises the Part-1 agent loop end-to-end (AgentService → VetoAgent → AgentRunner) with a
 * scripted {@link UniformLLMCaller}, the default McpEngine/CapabilityTranslator stubs, and the real
 * PromptCompiler / Gateway / HITL / IngressDefense. This replaces the legacy test that drove the
 * pre-in-memory {@code Agent.builder} API (now retired) and needed a live DeepSeek key; this
 * variant runs deterministically without credentials or a Spring context.
 */
class AgentEndToEndTest {

    private static final Duration EPISODE_TIMEOUT = Duration.ofSeconds(10);

    /** Builds an {@link AgentService} wired with the default stubs + a scripted caller. */
    private static AgentService serviceWith(UniformLLMCaller caller) {
        ObjectMapper mapper = new ObjectMapper();
        PromptCompiler compiler = new PromptCompiler(new DefaultCapabilityTranslator(mapper));
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
                50L);
    }

    private static AgentRunner.LlmBinding binding(String systemPrompt) {
        return new AgentRunner.LlmBinding(
                ProviderType.DEEPSEEK,
                "stub-model",
                "stub-key",
                LlmOptions.defaults(),
                systemPrompt);
    }

    /** A caller that replays a fixed script of responses, in order. */
    private static UniformLLMCaller scripted(VetoResponse... responses) {
        ArrayDeque<VetoResponse> queue = new ArrayDeque<>(List.of(responses));
        return request -> {
            VetoResponse r = queue.poll();
            if (r == null) {
                throw new IllegalStateException("scripted caller exhausted");
            }
            return r;
        };
    }

    private static VetoResponse thoughtOn(String thought, String message, boolean finished) {
        return new VetoResponse(
                thought,
                List.of(),
                message,
                finished,
                new VetoResponse.Features(false, true),
                null);
    }

    private static VetoResponse thoughtOnWithCall(String thought, String message, ToolCall call) {
        return new VetoResponse(
                thought,
                List.of(call),
                message,
                false,
                new VetoResponse.Features(false, true),
                null);
    }

    @Test
    void autonomousLoopFinishesAndStreamsMessage() throws Exception {
        AtomicReference<String> streamed = new AtomicReference<>();
        AgentService service =
                serviceWith(scripted(thoughtOn("2 + 2 = 4.", "The answer is 4.", true)));

        AgentResult result =
                service.submit(
                        "finish-test",
                        "What is 2 + 2?",
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT,
                        streamed::set);

        assertTrue(result.success(), "episode should finish successfully");
        assertEquals("The answer is 4.", result.message());
        assertEquals("The answer is 4.", streamed.get(), "the user-facing message was streamed");

        VetoAgent agent = service.agent("finish-test");
        assertNotNull(agent);
        assertReturnsToIdle(agent);

        List<TurnType> types = agent.history().stream().map(TurnRecord::type).toList();
        assertTrue(types.contains(TurnType.USER_PROMPT));
        assertTrue(types.contains(TurnType.ASSISTANT_RESPONSE));
        assertFalse(types.contains(TurnType.TOOL_CALL));
    }

    @Test
    void autonomousLoopExecutesToolCallThenFinishes() throws Exception {
        List<String> streamed = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        AgentService service =
                serviceWith(
                        scripted(
                                thoughtOnWithCall(
                                        "I'll compute 2+2 via calc.",
                                        "Let me check.",
                                        new ToolCall("calc", Map.of("expr", "2+2"))),
                                thoughtOn("calc says 4.", "The answer is 4.", true)));

        AgentResult result =
                service.submit(
                        "tool-test",
                        "What is 2 + 2?",
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT,
                        streamed::add);

        assertTrue(result.success(), "episode should finish successfully after the tool call");
        assertEquals("The answer is 4.", result.message());
        assertTrue(streamed.contains("Let me check."));
        assertTrue(streamed.contains("The answer is 4."));

        VetoAgent agent = service.agent("tool-test");
        List<TurnType> types = agent.history().stream().map(TurnRecord::type).toList();
        assertTrue(types.contains(TurnType.TOOL_CALL), "the tool call was recorded");
        assertTrue(types.contains(TurnType.TOOL_RESPONSE), "the tool observation was recorded");
        assertTrue(types.contains(TurnType.ASSISTANT_RESPONSE));
        assertReturnsToIdle(agent);
    }

    /**
     * Polls until the agent parks back in IDLE after its episode (the loop completes then parks).
     */
    private static void assertReturnsToIdle(VetoAgent agent) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (agent.state() != AgentState.IDLE && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(
                AgentState.IDLE, agent.state(), "agent should return to IDLE after the episode");
    }
}
