package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.mcp.DefaultToolEngine;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.memory.TurnRecordEntity;
import top.focess.veto.memory.TurnRecordRepository;
import top.focess.veto.vault.UserContext;

/**
 * Tests that per-user identity is threaded from the transport through {@link AgentService#submit}
 * to {@link AgentRunner}, ensuring the raw-turn log and group ownership use the supplied userId
 * instead of the default placeholder.
 */
class PerUserIdentityTest {

    private static final Duration EPISODE_TIMEOUT = Duration.ofSeconds(10);
    private static final UUID TEST_USER_ID =
            UUID.fromString("12345678-1234-1234-1234-123456789abc");

    private static AgentService serviceWith(UniformLLMCaller caller, TurnLogService turnLog) {
        ObjectMapper mapper = new ObjectMapper();
        PromptCompiler compiler =
                new PromptCompiler(
                        new DefaultCapabilityTranslator(mapper),
                        new SystemPromptResolver(),
                        mapper);
        ReflectionTestUtils.setField(compiler, "maxInputTokens", 32000);
        ReflectionTestUtils.setField(compiler, "contextFillRatio", 0.9);
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
                turnLog,
                new top.focess.veto.sandbox.BackgroundTaskManager(
                        new top.focess.veto.sandbox.SandboxManager(
                                new top.focess.veto.sandbox.ConstrainedSubprocessSubstrate())));
    }

    private static AgentRunner.LlmBinding binding() {
        return new AgentRunner.LlmBinding(
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
        TurnRecordRepository repo = org.mockito.Mockito.mock(TurnRecordRepository.class);
        TurnLogService turnLog = new TurnLogService(repo, new ObjectMapper());

        List<VetoRequest> seenRequests = new CopyOnWriteArrayList<>();
        UniformLLMCaller caller =
                request -> {
                    seenRequests.add(request);
                    return new VetoResponse(
                            "Done.",
                            List.of(),
                            "Task complete.",
                            new VetoResponse.Features(false),
                            null);
                };

        AgentService service = serviceWith(caller, turnLog);

        // Submit with explicit userId (this is the new API we're testing)
        AgentResult result =
                service.submit("test-agent", "Hello", binding(), EPISODE_TIMEOUT, TEST_USER_ID);

        assertTrue(result.success(), "Episode should complete successfully");

        // Verify turns were logged under the supplied userId, not DEFAULT_USER_ID
        org.mockito.ArgumentCaptor<TurnRecordEntity> captor =
                org.mockito.ArgumentCaptor.forClass(TurnRecordEntity.class);
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        TurnRecordEntity first = captor.getAllValues().get(0);
        assertEquals(
                TEST_USER_ID.toString(),
                first.getUserId(),
                "Logged turn should carry the supplied userId");
        assertNotEquals(
                AgentService.DEFAULT_USER_ID.toString(),
                first.getUserId(),
                "Logged userId should not be the default placeholder");
    }

    /**
     * The backwards-compatible overload (userId defaults to DEFAULT_USER_ID) still works and logs
     * under the default.
     */
    @Test
    void defaultUserIdUsedWhenNotSupplied() throws Exception {
        TurnRecordRepository repo = org.mockito.Mockito.mock(TurnRecordRepository.class);
        TurnLogService turnLog = new TurnLogService(repo, new ObjectMapper());

        UniformLLMCaller caller =
                request ->
                        new VetoResponse(
                                "Done.",
                                List.of(),
                                "Task complete.",
                                new VetoResponse.Features(false),
                                null);

        AgentService service = serviceWith(caller, turnLog);

        // Use the existing overload (no userId parameter)
        AgentResult result = service.submit("test-agent-default", "Hello", binding());

        assertTrue(result.success());

        // Turns logged under DEFAULT_USER_ID
        org.mockito.ArgumentCaptor<TurnRecordEntity> captor =
                org.mockito.ArgumentCaptor.forClass(TurnRecordEntity.class);
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        TurnRecordEntity first = captor.getAllValues().get(0);
        assertEquals(AgentService.DEFAULT_USER_ID.toString(), first.getUserId());
    }

    /**
     * Verify the session owner is stamped onto the agent's virtual thread (by {@link
     * AgentRunner#run}) so credential resolution on the LLM-call path resolves against the owner's
     * vault. The mocked caller executes synchronously on the agent thread, so it observes {@link
     * UserContext} as set by the runner.
     */
    @Test
    void ownerStampedOnAgentThreadForCredentialResolution() throws Exception {
        TurnRecordRepository repo = org.mockito.Mockito.mock(TurnRecordRepository.class);
        TurnLogService turnLog = new TurnLogService(repo, new ObjectMapper());

        List<String> seen = new CopyOnWriteArrayList<>();
        UniformLLMCaller caller =
                request -> {
                    seen.add(UserContext.get());
                    return new VetoResponse(
                            "Done.",
                            List.of(),
                            "Task complete.",
                            new VetoResponse.Features(false),
                            null);
                };

        AgentService service = serviceWith(caller, turnLog);

        String owner = "alice";
        String sessionId = UUID.randomUUID().toString();
        String primaryAgentId = UUID.randomUUID().toString();
        Agent agent =
                service.getOrCreateAgent(
                        sessionId,
                        primaryAgentId,
                        binding(),
                        List.of(),
                        service.userIdForOwner(owner),
                        owner,
                        null);
        agent.submit("Hello");
        AgentResult result = agent.await(EPISODE_TIMEOUT);

        assertTrue(result.success(), "Episode should complete successfully");
        assertFalse(seen.isEmpty(), "Caller should have been invoked on the agent thread");
        assertEquals(
                owner, seen.get(0), "UserContext on the agent thread must be the session owner");
    }

    /**
     * When no owner is threaded (the legacy/test submit path), the runner leaves {@link
     * UserContext} unset on the agent thread so the single-active-handle vault fallback still
     * applies.
     */
    @Test
    void nullOwnerLeavesUserContextUnset() throws Exception {
        TurnRecordRepository repo = org.mockito.Mockito.mock(TurnRecordRepository.class);
        TurnLogService turnLog = new TurnLogService(repo, new ObjectMapper());

        List<String> seen = new CopyOnWriteArrayList<>();
        UniformLLMCaller caller =
                request -> {
                    seen.add(UserContext.get());
                    return new VetoResponse(
                            "Done.",
                            List.of(),
                            "Task complete.",
                            new VetoResponse.Features(false),
                            null);
                };

        AgentService service = serviceWith(caller, turnLog);

        String agentKey = UUID.randomUUID().toString();
        AgentResult result =
                service.submit(agentKey, "Hello", binding(), EPISODE_TIMEOUT, TEST_USER_ID);

        assertTrue(result.success(), "Episode should complete successfully");
        assertFalse(seen.isEmpty(), "Caller should have been invoked on the agent thread");
        assertNull(seen.get(0), "UserContext must be unset when no owner is threaded");
    }
}
