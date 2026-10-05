package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import top.focess.veto.llm.core.ToolResultPresenter;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.memory.TurnRecordEntity;
import top.focess.veto.memory.TurnRecordRepository;
import top.focess.veto.vault.TestUsers;

/**
 * Verifies the turn-log wiring end-to-end: an agent's {@code appendTurn} (driven by a submitted
 * prompt) persists each turn to the raw-turn repository via {@link TurnLogService}. Turn
 * persistence is session state - nothing feeds LTM (agent-written only, via {@code write_memory}).
 */
class TurnLogWiringTest {

    private static final @NonNull Duration EPISODE_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void submittedEpisodeLogsTurnsIntoRawLog() throws Exception {
        TurnRecordRepository repo = mock(TurnRecordRepository.class);
        TurnLogService turnLog = new TurnLogService(repo, new ObjectMapper(), new DeltaBroker());

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
        AgentService service =
                AgentServiceTestSupport.create(
                        new AgentServiceTestSupport.Dependencies(),
                        new TestToolEngine(),
                        new HitlRegistry(null, Mockito.mock(SessionInvalidations.class)),
                        IngressDefenseTestSupport.inMemory(),
                        compiler,
                        callerFinishingImmediately(),
                        mapper,
                        List.of(),
                        "REAL",
                        50L,
                        "FULL_ACCESS",
                        "STRICT",
                        null,
                        turnLog);

        AgentResult result =
                service.submit(
                        "turn-log-test",
                        "What is 2 + 2?",
                        new LlmBinding(
                                ProviderType.DEEPSEEK,
                                "stub-model",
                                "stub-key",
                                LlmOptions.defaults(),
                                "sys"),
                        EPISODE_TIMEOUT,
                        TestUsers.OWNER);

        assertTrue(result.success(), "the episode finishes");
        ArgumentCaptor<TurnRecordEntity> records = ArgumentCaptor.forClass(TurnRecordEntity.class);
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
        return (request, modelSessionId) -> new VetoResponse("done", null, "4");
    }
}
