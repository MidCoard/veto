package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
import top.focess.veto.api.llm.VetoRequest;
import top.focess.veto.api.llm.VetoResponse;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.bus.SessionInvalidations;
import top.focess.veto.llm.core.ToolResultPresenter;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.memory.TurnRecordEntity;
import top.focess.veto.memory.TurnRecordRepository;
import top.focess.veto.vault.CurrentUser;
import top.focess.veto.vault.TestUsers;

/**
 * Tests that per-user identity is threaded from the transport through {@link AgentService#submit}
 * to {@link AgentRunner}, ensuring the raw-turn log and group ownership use the supplied userId
 * instead of the default placeholder.
 */
class PerUserIdentityTest {

    private static final @NonNull Duration EPISODE_TIMEOUT = Duration.ofSeconds(10);
    private static final @NonNull UUID TEST_USER_ID =
            UUID.fromString("12345678-1234-1234-1234-123456789abc");

    private static @NonNull AgentService serviceWith(
            @NonNull UniformLLMCaller caller, @NonNull TurnLogService turnLog) {
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
                null,
                turnLog);
    }

    private static @NonNull LlmBinding binding() {
        return new LlmBinding(
                ProviderType.DEEPSEEK,
                "stub-model",
                "stub-key",
                LlmOptions.defaults(),
                "You are a helpful assistant.");
    }

    /**
     * Verify that a supplied userId flows through submit → createAgent → AgentRunner → the raw-turn
     * log.
     */
    @Test
    void suppliedUserIdFlowsToTurnLog() throws Exception {
        TurnRecordRepository repo = Mockito.mock(TurnRecordRepository.class);
        TurnLogService turnLog = new TurnLogService(repo, new ObjectMapper(), new DeltaBroker());

        List<VetoRequest> seenRequests = new CopyOnWriteArrayList<>();
        UniformLLMCaller caller =
                (request, modelSessionId) -> {
                    seenRequests.add(request);
                    return new VetoResponse("Done.", null, "Task complete.");
                };

        AgentService service = serviceWith(caller, turnLog);

        // Submit with explicit userId (this is the new API we're testing)
        AgentResult result =
                service.submit("test-agent", "Hello", binding(), EPISODE_TIMEOUT, TEST_USER_ID);

        assertTrue(result.success(), "Episode should complete successfully");

        // Verify turns were logged under the supplied userId, not DEFAULT_USER_ID
        ArgumentCaptor<TurnRecordEntity> captor = ArgumentCaptor.forClass(TurnRecordEntity.class);
        Mockito.verify(repo, Mockito.atLeastOnce()).save(captor.capture());
        TurnRecordEntity first = captor.getAllValues().get(0);
        assertEquals(
                TEST_USER_ID, first.getUserId(), "Logged turn should carry the supplied userId");
    }

    /**
     * Verify the session owner is stamped onto the agent's virtual thread (by {@link
     * AgentRunner#run}) so credential resolution on the LLM-call path resolves against the owner's
     * vault. The mocked caller executes synchronously on the agent thread, so it observes {@link
     * CurrentUser} as set by the runner.
     */
    @Test
    void ownerStampedOnAgentThreadForCredentialResolution() throws Exception {
        TurnRecordRepository repo = Mockito.mock(TurnRecordRepository.class);
        TurnLogService turnLog = new TurnLogService(repo, new ObjectMapper(), new DeltaBroker());

        List<UUID> seen = new CopyOnWriteArrayList<>();
        UniformLLMCaller caller =
                (request, modelSessionId) -> {
                    UUID currentUser = CurrentUser.id();
                    if (currentUser != null) seen.add(currentUser);
                    return new VetoResponse("Done.", null, "Task complete.");
                };

        AgentService service = serviceWith(caller, turnLog);

        UUID userId = TestUsers.ALICE;
        String sessionId = UUID.randomUUID().toString();
        String primaryAgentId = UUID.randomUUID().toString();
        Agent agent =
                service.getOrCreateAgent(
                        sessionId, primaryAgentId, binding(), List.of(), userId, null);
        AgentResult result = agent.submitRequest("Hello").await(EPISODE_TIMEOUT);

        assertTrue(result.success(), "Episode should complete successfully");
        assertFalse(seen.isEmpty(), "Caller should have been invoked on the agent thread");
        assertEquals(
                userId, seen.get(0), "CurrentUser on the agent thread must be the session owner");
    }
}
