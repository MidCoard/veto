package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.mcp.DefaultToolEngine;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.memory.InMemoryMemoryStore;
import top.focess.veto.memory.MemoryCaptureService;
import top.focess.veto.memory.TurnRecordEntity;
import top.focess.veto.memory.TurnRecordRepository;
import top.focess.veto.memory.embedder.HashEmbedder;

/**
 * Verifies the capture wiring end-to-end: an agent's {@code appendTurn} (driven by a submitted
 * prompt) captures each turn into the Session LTM store AND the raw-turn repository via {@link
 * MemoryCaptureService}. Previously {@code MemoryCaptureService} was never called by the loop.
 */
class MemoryCaptureWiringTest {

    private static final Duration EPISODE_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void submittedEpisodeCapturesTurnsIntoStoreAndRawLog() throws Exception {
        InMemoryMemoryStore store = new InMemoryMemoryStore(new HashEmbedder());
        TurnRecordRepository repo = mock(TurnRecordRepository.class);
        MemoryCaptureService capture = new MemoryCaptureService(store, repo, new ObjectMapper());

        ObjectMapper mapper = new ObjectMapper();
        PromptCompiler compiler =
                new PromptCompiler(
                        new DefaultCapabilityTranslator(mapper),
                        Workspace.single(
                                Path.of(System.getProperty("user.dir", ".")), PathMode.REAL),
                        new SystemPromptResolver());
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
                        capture);

        AgentResult result =
                service.submit(
                        "capture-test",
                        "What is 2 + 2?",
                        new AgentRunner.LlmBinding(
                                ProviderType.DEEPSEEK,
                                "stub-model",
                                "stub-key",
                                LlmOptions.defaults(),
                                "sys"),
                        EPISODE_TIMEOUT);

        assertTrue(result.success(), "the episode finishes");
        assertFalse(store.size() == 0, "turns were captured into Session LTM");
        verify(repo, atLeastOnce()).save(any(TurnRecordEntity.class));
    }

    private static UniformLLMCaller callerFinishingImmediately() {
        return request ->
                new VetoResponse("done", List.of(), "4", new VetoResponse.Features(false), null);
    }
}
