package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.identity.RoleToolFilter;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.bus.DeltaFrame;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.sandbox.SandboxManager;
import top.focess.veto.sandbox.TestSandboxFactory;

/**
 * Verifies the Part-8 emission seam: an agent's user-facing message is published as a per-session
 * {@link DeltaFrame} to the {@link DeltaBroker} (which the {@code DeltaBusBridge} then forwards to
 * WebSocket clients). The broker assigns a monotonic sequence; the frame text is the message
 * verbatim. A {@code null} broker (the no-broker path) must not break the loop.
 */
class DeltaBrokerWiringTest {

    private static final Duration EPISODE_TIMEOUT = Duration.ofSeconds(10);

    private static @NonNull AgentService serviceWithBroker(
            @NonNull UniformLLMCaller caller, DeltaBroker broker) {
        ObjectMapper mapper = new ObjectMapper();
        PromptCompiler compiler =
                new PromptCompiler(
                        new DefaultCapabilityTranslator(mapper),
                        new SystemPromptResolver(),
                        mapper,
                        "FULL_ACCESS");
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
                50L,
                1000,
                "FULL_ACCESS",
                "STRICT",
                broker,
                null,
                new BackgroundTaskManager(
                        new SandboxManager(TestSandboxFactory.uncontainedSubprocesses())));
    }

    private static AgentRunner.@NonNull LlmBinding binding(@NonNull String systemPrompt) {
        return new AgentRunner.LlmBinding(
                ProviderType.DEEPSEEK,
                "stub-model",
                "stub-key",
                LlmOptions.defaults(),
                systemPrompt);
    }

    private static @NonNull UniformLLMCaller scripted(
            @NonNull VetoResponse @NonNull ... responses) {
        var queue = new ArrayDeque<>(List.of(responses));
        return request -> {
            VetoResponse r = queue.poll();
            if (r == null) {
                throw new IllegalStateException("scripted caller exhausted");
            }
            return r;
        };
    }

    private static @NonNull VetoResponse thoughtOn(String thought, String message) {
        return new VetoResponse(thought, null, message, null);
    }

    @Test
    void emitMessagePublishesDeltaFrameToBroker() throws Exception {
        DeltaBroker broker = new DeltaBroker();
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
        var content =
                frames.stream()
                        .filter(frame -> frame.kind() != DeltaFrame.Kind.SESSION_INVALIDATED)
                        .toList();
        assertEquals(3, content.size(), "thought, message and episode outcome remain distinct");
        assertEquals(DeltaFrame.Kind.ASSISTANT_THOUGHT, content.get(0).kind());
        assertEquals("2 + 2 = 4.", content.get(0).text());
        assertEquals(DeltaFrame.Kind.ASSISTANT_MESSAGE, content.get(1).kind());
        assertEquals("The answer is 4.", content.get(1).text());
        assertEquals(DeltaFrame.Kind.EPISODE_DONE, content.get(2).kind());
        assertTrue(content.get(0).sequence() < content.get(1).sequence());
        assertTrue(content.get(1).sequence() < content.get(2).sequence());
        assertTrue(
                frames.stream()
                        .anyMatch(
                                frame ->
                                        frame.kind() == DeltaFrame.Kind.SESSION_INVALIDATED
                                                && String.valueOf(frame.attrs().get("resources"))
                                                        .contains("execution")),
                "execution changes must reach clients independently of conversation content");
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
