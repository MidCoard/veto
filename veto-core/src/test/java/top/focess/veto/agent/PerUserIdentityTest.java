package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.mcp.DefaultMcpEngine;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.memory.InMemoryMemoryStore;
import top.focess.veto.memory.Memory;
import top.focess.veto.memory.MemoryCaptureService;

/**
 * Tests that per-user identity is threaded from the transport through {@link AgentService#submit}
 * to {@link AgentRunner}, ensuring memory capture and group ownership use the supplied userId
 * instead of the default placeholder.
 */
class PerUserIdentityTest {

    private static final Duration EPISODE_TIMEOUT = Duration.ofSeconds(10);
    private static final UUID TEST_USER_ID =
            UUID.fromString("12345678-1234-1234-1234-123456789abc");

    private static AgentService serviceWith(UniformLLMCaller caller, MemoryCaptureService capture) {
        ObjectMapper mapper = new ObjectMapper();
        PromptCompiler compiler =
                new PromptCompiler(
                        new DefaultCapabilityTranslator(mapper),
                        Workspace.single(
                                Path.of(System.getProperty("user.dir", ".")), PathMode.REAL));
        ReflectionTestUtils.setField(compiler, "maxInputTokens", 32000);
        ReflectionTestUtils.setField(compiler, "contextFillRatio", 0.9);
        return new AgentService(
                new DefaultMcpEngine(),
                new HitlRegistry(),
                new IngressDefense(),
                compiler,
                caller,
                mapper,
                List.of(),
                System.getProperty("user.dir", "."),
                "",
                "REAL",
                50L,
                "FULL_ACCESS",
                "STRICT",
                null,
                capture);
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
     * RED: Verify that a supplied userId flows through submit → createAgent → AgentRunner → memory
     * capture. The existing submit overload has no userId parameter, so this test will fail to
     * compile until the new overload is added.
     */
    @Test
    void suppliedUserIdFlowsToMemoryCapture() throws Exception {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        MemoryCaptureService capture = new MemoryCaptureService(store, null, null);

        List<VetoRequest> seenRequests = new CopyOnWriteArrayList<>();
        UniformLLMCaller caller =
                request -> {
                    seenRequests.add(request);
                    return new VetoResponse(
                            "Done.",
                            List.of(),
                            "Task complete.",
                            true,
                            new VetoResponse.Features(false, true),
                            null);
                };

        AgentService service = serviceWith(caller, capture);

        // Submit with explicit userId (this is the new API we're testing)
        AgentResult result =
                service.submit("test-agent", "Hello", binding(), EPISODE_TIMEOUT, TEST_USER_ID);

        assertTrue(result.success(), "Episode should complete successfully");

        // Verify memory was captured under the supplied userId, not DEFAULT_USER_ID
        // The memory is captured under SESSION tier, so use snapshot for direct inspection
        var snapshot = store.snapshot();
        assertFalse(snapshot.isEmpty(), "Memory should be captured");

        // Find a memory for our userId
        Memory forOurUser =
                snapshot.values().stream()
                        .filter(m -> m.userId().equals(TEST_USER_ID))
                        .findFirst()
                        .orElse(null);
        assertNotNull(forOurUser, "Memory should be captured for the supplied userId");
        assertEquals(
                TEST_USER_ID, forOurUser.userId(), "Captured memory should have supplied userId");
        assertNotEquals(
                AgentService.DEFAULT_USER_ID,
                forOurUser.userId(),
                "Captured userId should not be the default placeholder");
    }

    /**
     * GREEN path: the backwards-compatible overload (userId defaults to DEFAULT_USER_ID) still
     * works and captures under the default.
     */
    @Test
    void defaultUserIdUsedWhenNotSupplied() throws Exception {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        MemoryCaptureService capture = new MemoryCaptureService(store, null, null);

        UniformLLMCaller caller =
                request ->
                        new VetoResponse(
                                "Done.",
                                List.of(),
                                "Task complete.",
                                true,
                                new VetoResponse.Features(false, true),
                                null);

        AgentService service = serviceWith(caller, capture);

        // Use the existing overload (no userId parameter)
        AgentResult result = service.submit("test-agent-default", "Hello", binding());

        assertTrue(result.success());

        // Memory captured under DEFAULT_USER_ID
        var snapshot = store.snapshot();
        assertFalse(snapshot.isEmpty());
        Memory first = snapshot.values().iterator().next();
        assertEquals(AgentService.DEFAULT_USER_ID, first.userId());
    }
}
