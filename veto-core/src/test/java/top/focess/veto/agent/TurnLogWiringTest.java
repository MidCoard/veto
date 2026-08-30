package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
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
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.memory.TurnRecordEntity;
import top.focess.veto.memory.TurnRecordRepository;

/**
 * Verifies the turn-log wiring end-to-end: an agent's {@code appendTurn} (driven by a submitted
 * prompt) persists each turn to the raw-turn repository via {@link TurnLogService}. Turn
 * persistence is session state - nothing feeds LTM (agent-written only, via {@code write_memory}).
 */
class TurnLogWiringTest {

    private static final @NonNull Duration EPISODE_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void submittedEpisodeLogsTurnsIntoRawLog() throws Exception {
        TurnRecordRepository repo = mock(ToolDocs.nonNullClass(TurnRecordRepository.class));
        TurnLogService turnLog = new TurnLogService(repo, new ObjectMapper());

        ObjectMapper mapper = new ObjectMapper();
        PromptCompiler compiler =
                new PromptCompiler(
                        new DefaultCapabilityTranslator(mapper),
                        new SystemPromptResolver(),
                        mapper);
        ReflectionTestUtils.setField(compiler, "maxInputTokens", 32000);
        ReflectionTestUtils.setField(compiler, "contextFillRatio", 0.9);
        AgentService service =
                new AgentService(
                        new DefaultToolEngine(),
                        new HitlRegistry(),
                        new IngressDefense(),
                        compiler,
                        callerFinishingImmediately(),
                        mapper,
                        List.of(),
                        new top.focess.veto.agent.identity.RoleToolFilter(new DefaultToolEngine()),
                        "REAL",
                        50L,
                        "FULL_ACCESS",
                        "STRICT",
                        null,
                        turnLog,
                        new top.focess.veto.sandbox.BackgroundTaskManager(
                                new top.focess.veto.sandbox.SandboxManager(
                                        new top.focess.veto.sandbox
                                                .ConstrainedSubprocessSubstrate())));

        AgentResult result =
                service.submit(
                        "turn-log-test",
                        "What is 2 + 2?",
                        new AgentRunner.LlmBinding(
                                ProviderType.DEEPSEEK,
                                "stub-model",
                                "stub-key",
                                LlmOptions.defaults(),
                                "sys"),
                        EPISODE_TIMEOUT);

        assertTrue(result.success(), "the episode finishes");
        ArgumentCaptor<TurnRecordEntity> records =
                ArgumentCaptor.forClass(ToolDocs.nonNullClass(TurnRecordEntity.class));
        verify(repo, atLeastOnce()).save(records.capture());
        List<TurnRecordEntity> persisted = records.getAllValues();
        assertEquals("AGENT_INIT", persisted.get(0).getType());
        assertEquals(1, persisted.get(0).getTurnNumber());
        assertEquals("USER_PROMPT", persisted.get(1).getType());
        assertEquals(2, persisted.get(1).getTurnNumber());
        assertTrue(
                persisted.stream().anyMatch(row -> "AGENT_INIT".equals(row.getType())),
                "the agent init definition is part of the durable session record");
        TurnRecordEntity agentInit =
                persisted.stream()
                        .filter(row -> "AGENT_INIT".equals(row.getType()))
                        .findFirst()
                        .orElseThrow();
        assertTrue(agentInit.getPayload().contains("stub-model"));
        assertTrue(agentInit.getPayload().contains("DEEPSEEK"));
        assertTrue(agentInit.getPayload().contains("system_prompt"));
    }

    private static @NonNull UniformLLMCaller callerFinishingImmediately() {
        return request ->
                new VetoResponse("done", List.of(), "4", new VetoResponse.Features(false), null);
    }
}
