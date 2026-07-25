package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.mcp.DefaultMcpEngine;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.bus.DeltaFrame;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoResponse;

/**
 * Verifies the Part-8 emission seam: an agent's user-facing message is published as a per-session
 * {@link DeltaFrame} to the {@link DeltaBroker} (which the {@code DeltaBusBridge} then forwards to
 * WebSocket clients). The broker assigns a monotonic sequence; the frame text is the message
 * verbatim. A {@code null} broker (the no-broker path) must not break the loop.
 */
class DeltaBrokerWiringTest {

    private static final Duration EPISODE_TIMEOUT = Duration.ofSeconds(10);

    private static AgentService serviceWithBroker(UniformLLMCaller caller, DeltaBroker broker) {
        ObjectMapper mapper = new ObjectMapper();
        PromptCompiler compiler =
                new PromptCompiler(
                        new DefaultCapabilityTranslator(mapper),
                        Workspace.single(
                                Path.of(System.getProperty("user.dir", ".")), PathMode.REAL),
                        new SystemPromptResolver());
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
                new top.focess.veto.agent.identity.RoleToolFilter(new DefaultMcpEngine()),
                "REAL",
                50L,
                "FULL_ACCESS",
                "STRICT",
                broker,
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

    private static UniformLLMCaller scripted(VetoResponse... responses) {
        var queue = new java.util.ArrayDeque<>(List.of(responses));
        return request -> {
            VetoResponse r = queue.poll();
            if (r == null) {
                throw new IllegalStateException("scripted caller exhausted");
            }
            return r;
        };
    }

    private static VetoResponse thoughtOn(String thought, String message) {
        return new VetoResponse(
                thought, List.of(), message, new VetoResponse.Features(false), null);
    }

    @Test
    void emitMessagePublishesDeltaFrameToBroker() throws Exception {
        DeltaBroker broker = new DeltaBroker(new ObjectMapper());
        List<DeltaFrame> frames = new CopyOnWriteArrayList<>();
        broker.subscribeAll(frames::add);

        AgentService service =
                serviceWithBroker(scripted(thoughtOn("2 + 2 = 4.", "The answer is 4.")), broker);
        AgentResult result =
                service.submit(
                        "delta-wire",
                        "What is 2 + 2?",
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT);

        assertTrue(result.success(), "episode should finish successfully");
        assertFalse(frames.isEmpty(), "a DeltaFrame should be published on emitMessage");
        DeltaFrame f = frames.get(0);
        assertEquals(DeltaFrame.Kind.ASSISTANT_MESSAGE, f.kind());
        assertEquals("The answer is 4.", f.text());
        assertEquals(1L, f.sequence(), "broker assigns a monotonic sequence starting at 1");
    }

    @Test
    void nullBrokerDoesNotBreakTheLoop() throws Exception {
        // deltaBroker = null: the publish is skipped, the loop still runs and emits normally.
        AgentService service =
                serviceWithBroker(scripted(thoughtOn("2 + 2 = 4.", "The answer is 4.")), null);
        AgentResult result =
                service.submit(
                        "delta-null",
                        "What is 2 + 2?",
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT);
        assertTrue(result.success(), "a null broker must not break the loop");
    }
}
