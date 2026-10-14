package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import top.focess.veto.api.llm.LlmSystemUsage;
import top.focess.veto.api.llm.ProviderType;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.bus.SessionActions;
import top.focess.veto.contract.Delta;
import top.focess.veto.contract.Frame;
import top.focess.veto.llm.core.ToolResultPresenter;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.session.TestAgentSessions;
import top.focess.veto.session.TestConnection;
import top.focess.veto.vault.TestUsers;

/**
 * Verifies the Part-8 emission seam: an agent's user-facing message is published as a per-session
 * {@link Delta} to the {@link AgentSessions} attachment runtime. The owning session assigns a
 * monotonic sequence; the frame text is the message verbatim.
 */
class AgentSessionWiringTest {

    private static final Duration EPISODE_TIMEOUT = Duration.ofSeconds(10);

    private static @NonNull AgentService serviceWithSession(
            @NonNull UniformLLMCaller caller, @NonNull AgentSessions runtime) {
        return serviceWithSession(caller, runtime, 50L);
    }

    private static @NonNull AgentService serviceWithSession(
            @NonNull UniformLLMCaller caller, @NonNull AgentSessions runtime, long budget) {
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
                new HitlRegistry(null, Mockito.mock(SessionActions.class)),
                IngressDefenseTestSupport.inMemory(),
                compiler,
                caller,
                mapper,
                List.of(),
                "REAL",
                budget,
                "FULL_ACCESS",
                "STRICT",
                runtime,
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
    void executionActionsArriveBeforeModelWorkAndAfterCompletion() throws Exception {
        var runtime = TestAgentSessions.create();
        var frames = new CopyOnWriteArrayList<Frame.ScopedFrame>();
        var idleUpdate = new CountDownLatch(1);
        runtime.connectBrowser(
                new TestConnection(
                                TestUsers.OWNER,
                                frame -> {
                                    frames.add(frame);
                                    if (frame instanceof Frame.Action
                                            && frames.stream()
                                                    .anyMatch(Frame.Done.class::isInstance))
                                        idleUpdate.countDown();
                                })
                        .connection());
        var service =
                serviceWithSession(
                        (request, session) -> {
                            assertTrue(frames.stream().anyMatch(Frame.Action.class::isInstance));
                            return thoughtOn(null, "done");
                        },
                        runtime);
        try {
            assertTrue(
                    service.submit(
                                    "action-wire",
                                    "hello",
                                    binding(""),
                                    EPISODE_TIMEOUT,
                                    TestUsers.OWNER)
                            .success());
            assertTrue(
                    idleUpdate.await(5, TimeUnit.SECONDS),
                    "Idle state must refresh after the turn result");
            var done =
                    frames.stream().filter(Frame.Done.class::isInstance).findFirst().orElseThrow();
            for (var frame : frames) {
                if (frame instanceof Frame.Action action) {
                    assertEquals(done.sessionId(), action.sessionId());
                    assertEquals(TestUsers.OWNER, action.scope().userId());
                    assertEquals(List.of("agents", "execution"), action.resources());
                }
            }
        } finally {
            service.remove("action-wire");
        }
    }

    @Test
    void measuredModelCallsPublishUsageBeforeCompletion() throws Exception {
        var runtime = TestAgentSessions.create();
        var frames = new CopyOnWriteArrayList<Frame.ScopedFrame>();
        runtime.connectBrowser(new TestConnection(TestUsers.OWNER, frames::add).connection());
        var service =
                serviceWithSession(
                        (request, session) -> {
                            LlmSystemUsage.set(100, 20, 30L, null);
                            LlmSystemUsage.set(140, 25);
                            return thoughtOn(null, "answer");
                        },
                        runtime);
        try {
            assertTrue(
                    service.submit(
                                    "usage-wire",
                                    "hello",
                                    binding(""),
                                    EPISODE_TIMEOUT,
                                    TestUsers.OWNER)
                            .success());
            var updates =
                    frames.stream()
                            .filter(
                                    frame ->
                                            frame instanceof Delta delta
                                                    && delta.data() instanceof Delta.TokenUsage)
                            .toList();
            assertEquals(2, updates.size());
            var first =
                    assertInstanceOf(Delta.TokenUsage.class, ((Delta) updates.getFirst()).data());
            var second =
                    assertInstanceOf(Delta.TokenUsage.class, ((Delta) updates.getLast()).data());
            assertEquals(100, first.inputTokens());
            assertEquals(20, first.outputTokens());
            var cached = first.cacheReadInputTokens();
            assertTrue(cached != null && cached == 30L);
            assertNull(second.cacheReadInputTokens());
            assertNotEquals(first.modelCallId(), second.modelCallId());
            assertTrue(
                    frames.indexOf(updates.getLast())
                            < frames.indexOf(
                                    frames.stream()
                                            .filter(Frame.Done.class::isInstance)
                                            .findFirst()
                                            .orElseThrow()));
        } finally {
            service.remove("usage-wire");
        }
    }

    @Test
    void breakerPublishesNoticeThenDoneAndWaitsForTheNextUserRequest() throws Exception {
        var runtime = TestAgentSessions.create();
        var frames = new CopyOnWriteArrayList<Frame.ScopedFrame>();
        runtime.connectBrowser(new TestConnection(TestUsers.OWNER, frames::add).connection());
        var service =
                serviceWithSession(
                        scripted(
                                new VetoResponse(
                                        null,
                                        List.of(new ToolCall("missing", Map.of(), "call")),
                                        null),
                                thoughtOn(null, "resumed")),
                        runtime,
                        1L);
        var result =
                service.submit(
                        "breaker-wire", "start", binding(""), EPISODE_TIMEOUT, TestUsers.OWNER);
        assertEquals(Boolean.TRUE, result.metadata().get("breakerTrip"));
        var notice =
                frames.stream()
                        .filter(
                                frame ->
                                        frame instanceof Delta delta
                                                && delta.data() instanceof Delta.Notice)
                        .findFirst()
                        .orElseThrow();
        var outcome =
                frames.stream()
                        .filter(Frame.TurnOutcome.class::isInstance)
                        .findFirst()
                        .orElseThrow();
        assertInstanceOf(Frame.Done.class, outcome);
        assertTrue(frames.indexOf(notice) < frames.indexOf(outcome));
        assertFalse(frames.stream().anyMatch(Frame.Error.class::isInstance));
        assertTrue(
                service.submit(
                                "breaker-wire",
                                "continue",
                                binding(""),
                                EPISODE_TIMEOUT,
                                TestUsers.OWNER)
                        .success());
        service.remove("breaker-wire");
    }

    @Test
    void failedEpisodePublishesErrorWithoutDone() throws Exception {
        var runtime = TestAgentSessions.create();
        var frames = new CopyOnWriteArrayList<Frame.ScopedFrame>();
        runtime.connectBrowser(new TestConnection(TestUsers.OWNER, frames::add).connection());
        var service =
                serviceWithSession(
                        (request, modelSessionId) -> {
                            throw new IllegalStateException("test provider failure");
                        },
                        runtime);
        var result =
                service.submit("failed-wire", "Hi", binding(""), EPISODE_TIMEOUT, TestUsers.OWNER);
        assertFalse(result.success());
        var terminal = frames.stream().filter(Frame.TurnOutcome.class::isInstance).toList();
        assertEquals(1, terminal.size());
        var error = assertInstanceOf(Frame.Error.class, terminal.getFirst());
        assertTrue(error.sessionId() != null);
        assertTrue(error.agentId() != null);
        assertTrue(error.requestId() != null);
        assertEquals(result.message(), error.content());
    }

    @Test
    void emitMessagePublishesDeltaToBroker() throws Exception {
        var runtime = TestAgentSessions.create();
        List<Frame.ScopedFrame> frames = new CopyOnWriteArrayList<>();
        runtime.connectBrowser(new TestConnection(TestUsers.OWNER, frames::add).connection());

        AgentService service =
                serviceWithSession(scripted(thoughtOn("2 + 2 = 4.", "The answer is 4.")), runtime);
        AgentResult result =
                service.submit(
                        "delta-wire",
                        "What is 2 + 2?",
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT,
                        TestUsers.OWNER);

        assertTrue(result.success(), "episode should finish successfully");
        assertFalse(frames.isEmpty(), "a Delta should be published on emitMessage");
        var content = frames.stream().filter(frame -> !(frame instanceof Frame.Action)).toList();
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
    }
}
