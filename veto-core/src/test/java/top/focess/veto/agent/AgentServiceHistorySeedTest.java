package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.identity.RoleToolFilter;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.sandbox.SandboxManager;
import top.focess.veto.sandbox.TestSandboxFactory;

/**
 * Verifies {@link AgentService#getOrCreateAgent} seeds replayed history on first creation (so a
 * re-activated session resumes its conversation) and is idempotent - a second get-or-create on the
 * same session returns the same agent without re-seeding (no duplicated turns).
 */
class AgentServiceHistorySeedTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void getOrCreateSeedsHistoryOnFirstCall() {
        // The caller is never invoked: getOrCreateAgent creates + binds + seeds but does not
        // submit.
        UniformLLMCaller caller =
                request -> {
                    throw new AssertionError("LLM call not expected");
                };
        AgentService service = serviceWith(caller);
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AgentRunner.LlmBinding binding = binding();

        List<TurnRecord> history =
                List.of(TurnRecord.userPrompt(1, "prior"), TurnRecord.assistantResponse(2, "ok"));

        Agent a1 = service.getOrCreateAgent(sessionId.toString(), binding, history, userId);
        assertEquals(2, a1.history().size(), "history seeded on first create");

        // Second call returns the same agent WITHOUT re-seeding (history stays 2, not 4).
        Agent a2 = service.getOrCreateAgent(sessionId.toString(), binding, history, userId);
        assertSame(a1, a2, "same agent returned for the same session id");
        assertEquals(2, a2.history().size(), "history not re-seeded on second get-or-create");
    }

    @Test
    void getOrCreateWithEmptyHistoryDoesNotSeed() {
        UniformLLMCaller caller =
                request -> {
                    throw new AssertionError("LLM call not expected");
                };
        AgentService service = serviceWith(caller);
        UUID sessionId = UUID.randomUUID();
        AgentRunner.LlmBinding binding = binding();

        Agent a =
                service.getOrCreateAgent(
                        sessionId.toString(), binding, List.of(), UUID.randomUUID());
        assertTrue(a.history().isEmpty(), "empty replay history leaves the agent's history empty");
    }

    @Test
    void seedHistoryAdvancesTurnNumberPastReplayedTurns() {
        UniformLLMCaller caller =
                request -> {
                    throw new AssertionError("LLM call not expected");
                };
        AgentService service = serviceWith(caller);
        UUID sessionId = UUID.randomUUID();
        AgentRunner.LlmBinding binding = binding();
        // Replayed history with a gap (1, 2, 5) so the max is 5, not the turn count.
        List<TurnRecord> history =
                List.of(
                        TurnRecord.userPrompt(1, "a"),
                        TurnRecord.assistantResponse(2, "b"),
                        TurnRecord.userPrompt(5, "c"));

        Agent a =
                service.getOrCreateAgent(sessionId.toString(), binding, history, UUID.randomUUID());
        AgentRunner runner =
                assertInstanceOf(
                        ToolDocs.nonNullClass(AgentRunner.class),
                        requireField(ReflectionTestUtils.getField(a, "runner")));
        int turnNumber =
                assertInstanceOf(
                        ToolDocs.nonNullClass(Integer.class),
                        requireField(ReflectionTestUtils.getField(runner, "turnNumber")));
        assertEquals(5, turnNumber, "seedHistory advances turnNumber to the max replayed turn");
    }

    @Test
    void restartedAgentUsesLastOrderedSystemSnapshotWithoutAddingAnother() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AgentRunner.LlmBinding binding = binding();
        UniformLLMCaller finishingCaller =
                request ->
                        new VetoResponse(
                                "done", List.of(), "done", new VetoResponse.Features(false), null);

        AgentService beforeRestart = serviceWith(finishingCaller);
        Agent first =
                beforeRestart.getOrCreateAgent(sessionId.toString(), binding, List.of(), userId);
        first.submit("first request");
        assertTrue(first.await(TIMEOUT).success());
        List<TurnRecord> replayed = new ArrayList<>(first.history());
        assertEquals(TurnType.AGENT_INIT, replayed.get(0).type());
        String transformedSystemPrompt = "system prompt recorded by a later role transition";
        int nextTurn = replayed.stream().mapToInt(TurnRecord::turnNumber).max().orElseThrow() + 1;
        replayed.add(
                TurnRecord.agentInit(
                        nextTurn, "LEADER", transformedSystemPrompt, "DEEPSEEK", "stub"));
        assertEquals(2, count(replayed, TurnType.AGENT_INIT));
        first.terminate();

        AtomicReference<VetoRequest> resumedRequest = new AtomicReference<>();
        AgentService afterRestart =
                serviceWith(
                        request -> {
                            resumedRequest.set(request);
                            return new VetoResponse(
                                    "done",
                                    List.of(),
                                    "done",
                                    new VetoResponse.Features(false),
                                    null);
                        });
        AgentRunner.LlmBinding updatedPromptBinding = binding("updated after restart");
        Agent resumed =
                afterRestart.getOrCreateAgent(
                        sessionId.toString(), updatedPromptBinding, replayed, userId);
        resumed.submit("second request");
        assertTrue(resumed.await(TIMEOUT).success());

        assertEquals(2, count(resumed.history(), TurnType.AGENT_INIT));
        assertEquals(2, count(resumed.history(), TurnType.USER_PROMPT));
        VetoRequest request =
                assertInstanceOf(ToolDocs.nonNullClass(VetoRequest.class), resumedRequest.get());
        assertEquals("system", request.messages().get(0).role());
        assertEquals(
                transformedSystemPrompt,
                request.messages().get(0).content(),
                "resume must use the last AGENT_INIT in durable record order");
        assertFalse(
                request.messages().get(0).content().contains("updated after restart"),
                "a changed runtime template must not replace the durable system insertion");
        assertTrue(
                request.messages().stream()
                        .anyMatch(message -> "first request".equals(message.content())));
        assertTrue(
                request.messages().stream()
                        .anyMatch(message -> "second request".equals(message.content())));
        resumed.terminate();
    }

    private static @NonNull AgentService serviceWith(@NonNull UniformLLMCaller caller) {
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
                null,
                new TurnLogService(null, mapper),
                new BackgroundTaskManager(
                        new SandboxManager(TestSandboxFactory.uncontainedSubprocesses())));
    }

    private static AgentRunner.@NonNull LlmBinding binding() {
        return binding(null);
    }

    private static AgentRunner.@NonNull LlmBinding binding(String systemPromptBase) {
        return new AgentRunner.LlmBinding(
                ProviderType.DEEPSEEK,
                "stub-model",
                "stub-key",
                LlmOptions.defaults(),
                systemPromptBase);
    }

    private static @NonNull Object requireField(Object value) {
        if (value == null) throw new AssertionError("expected reflected field");
        return value;
    }

    private static long count(@NonNull List<TurnRecord> history, @NonNull TurnType type) {
        return history.stream().filter(turn -> turn.type() == type).count();
    }
}
