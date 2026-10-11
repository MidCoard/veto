package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefenseTestSupport;
import top.focess.veto.agent.loop.ContextBudgetConfiguration;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.translation.VetoCapabilityTranslator;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.llm.LlmOptions;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.bus.SessionInvalidations;
import top.focess.veto.contract.Delta;
import top.focess.veto.contract.Frame;
import top.focess.veto.llm.core.ToolResultPresenter;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.vault.TestUsers;

/**
 * Verifies the Part-8 emission seam: an agent's user-facing message is published as a per-session
 * {@link Delta} to the {@link DeltaBroker} (which the {@code DeltaBusBridge} then forwards to
 * WebSocket clients). The broker assigns a monotonic sequence; the frame text is the message
 * verbatim.
 */
class DeltaBrokerWiringTest {

    private static final Duration EPISODE_TIMEOUT = Duration.ofSeconds(10);

    private static @NonNull AgentService serviceWithBroker(
            @NonNull UniformLLMCaller caller, @NonNull DeltaBroker broker) {
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
                new AgentServiceTestSupport.Dependencies(),
                new TestToolEngine(),
                new HitlRegistry(null, Mockito.mock(SessionInvalidations.class)),
                IngressDefenseTestSupport.inMemory(),
                compiler,
                caller,
                mapper,
                List.of(),
                "REAL",
                50L,
                "FULL_ACCESS",
                "STRICT",
                broker,
                null);
    }

    private static @NonNull LlmBinding binding(@NonNull String ignoredPrompt) {
        return new LlmBinding(
                ProviderType.DEEPSEEK, "stub-model", "stub-key", LlmOptions.defaults(), null);
    }

    private static @NonNull UniformLLMCaller scripted(
            @NonNull VetoResponse @NonNull ... responses) {
        var queue = new ArrayDeque<>(List.of(responses));
        return (request, modelSessionId) -> {
            VetoResponse r = queue.poll();
            if (r == null) {
                throw new IllegalStateException("scripted caller exhausted");
            }
            return r;
        };
    }

    private static @NonNull VetoResponse thoughtOn(String thought, String message) {
        return new VetoResponse(thought, null, message);
    }

    @Test
    void failedEpisodePublishesErrorWithoutDone() throws Exception {
        var broker = new DeltaBroker();
        var frames = new CopyOnWriteArrayList<Frame.ScopedFrame>();
        broker.subscribeAll(frames::add);
        var service =
                serviceWithBroker(
                        (request, modelSessionId) -> {
                            throw new IllegalStateException("test provider failure");
                        },
                        broker);
        var result =
                service.submit("failed-wire", "Hi", binding(""), EPISODE_TIMEOUT, TestUsers.OWNER);
        assertFalse(result.success());
        var terminal = frames.stream().filter(Frame.TerminalResponse.class::isInstance).toList();
        assertEquals(1, terminal.size());
        var error = assertInstanceOf(Frame.Error.class, terminal.getFirst());
        assertTrue(error.sessionId() != null);
        assertTrue(error.agentId() != null);
        assertTrue(error.requestId() != null);
        assertEquals(result.message(), error.content());
    }

    @Test
    void emitMessagePublishesDeltaToBroker() throws Exception {
        DeltaBroker broker = new DeltaBroker();
        List<Frame.ScopedFrame> frames = new CopyOnWriteArrayList<>();
        broker.subscribeAll(frames::add);

        AgentService service =
                serviceWithBroker(scripted(thoughtOn("2 + 2 = 4.", "The answer is 4.")), broker);
        AgentResult result =
                service.submit(
                        "delta-wire",
                        "What is 2 + 2?",
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT,
                        TestUsers.OWNER);

        assertTrue(result.success(), "episode should finish successfully");
        assertFalse(frames.isEmpty(), "a Delta should be published on emitMessage");
        var content =
                frames.stream()
                        .filter(
                                frame ->
                                        !(frame instanceof Delta delta
                                                && delta.data()
                                                        instanceof Delta.SessionInvalidated))
                        .toList();
        assertEquals(3, content.size(), "thought, message and completion remain distinct");
        var thought = assertInstanceOf(Delta.class, content.get(0));
        var message = assertInstanceOf(Delta.class, content.get(1));
        var done = assertInstanceOf(Frame.Done.class, content.get(2));
        assertEquals("2 + 2 = 4.", assertInstanceOf(Delta.Thought.class, thought.data()).text());
        assertEquals(
                "The answer is 4.", assertInstanceOf(Delta.Message.class, message.data()).text());
        assertTrue(thought.sequence() < message.sequence());
        assertEquals(message.sessionId(), done.sessionId());
        assertEquals(message.agentId(), done.agentId());
        assertTrue(done.requestId() != null);
        assertTrue(
                frames.stream()
                        .anyMatch(
                                frame ->
                                        frame instanceof Delta delta
                                                && delta.data()
                                                        instanceof
                                                        Delta.SessionInvalidated invalidation
                                                && invalidation.resources().contains("execution")),
                "execution changes must reach clients independently of conversation content");
    }
}
