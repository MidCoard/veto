package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.mcp.DefaultToolEngine;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.UniformLLMCaller;

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
                        requireField(
                                org.springframework.test.util.ReflectionTestUtils.getField(
                                        a, "runner")));
        int turnNumber =
                assertInstanceOf(
                        ToolDocs.nonNullClass(Integer.class),
                        requireField(
                                org.springframework.test.util.ReflectionTestUtils.getField(
                                        runner, "turnNumber")));
        assertEquals(5, turnNumber, "seedHistory advances turnNumber to the max replayed turn");
    }

    private static @NonNull AgentService serviceWith(@NonNull UniformLLMCaller caller) {
        ObjectMapper mapper = new ObjectMapper();
        PromptCompiler compiler =
                new PromptCompiler(
                        new DefaultCapabilityTranslator(mapper),
                        new SystemPromptResolver(),
                        mapper);
        org.springframework.test.util.ReflectionTestUtils.setField(
                compiler, "maxInputTokens", 32000);
        org.springframework.test.util.ReflectionTestUtils.setField(
                compiler, "contextFillRatio", 0.9);
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
                50L,
                "FULL_ACCESS",
                "STRICT",
                null,
                new top.focess.veto.memory.TurnLogService(null, mapper),
                new top.focess.veto.sandbox.BackgroundTaskManager(
                        new top.focess.veto.sandbox.SandboxManager(
                                new top.focess.veto.sandbox.ConstrainedSubprocessSubstrate())));
    }

    private static AgentRunner.@NonNull LlmBinding binding() {
        return new AgentRunner.LlmBinding(
                ProviderType.DEEPSEEK, "stub-model", "stub-key", LlmOptions.defaults(), null);
    }

    private static @NonNull Object requireField(Object value) {
        if (value == null) throw new AssertionError("expected reflected field");
        return value;
    }
}
