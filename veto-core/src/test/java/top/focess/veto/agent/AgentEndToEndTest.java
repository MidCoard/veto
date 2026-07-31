package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.DefaultToolEngine;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.mcp.ToolEngine;
import top.focess.veto.agent.mcp.ToolResult;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoResponse;

/**
 * Exercises the agent loop end-to-end (AgentService → VetoAgent → AgentRunner) with a scripted
 * {@link UniformLLMCaller}, the default ToolEngine/CapabilityTranslator stubs, and the real
 * PromptCompiler / Gateway / HITL / IngressDefense. This replaces the legacy test that drove the
 * pre-in-memory {@code Agent.builder} API (now retired) and needed a live DeepSeek key; this
 * variant runs deterministically without credentials or a Spring context.
 */
class AgentEndToEndTest {

    private static final Duration EPISODE_TIMEOUT = Duration.ofSeconds(10);

    /** Builds an {@link AgentService} wired with the default stubs + a scripted caller. */
    private static AgentService serviceWith(UniformLLMCaller caller) {
        ObjectMapper mapper = new ObjectMapper();
        PromptCompiler compiler =
                new PromptCompiler(
                        new DefaultCapabilityTranslator(mapper),
                        Workspace.single(
                                Path.of(System.getProperty("user.dir", ".")), PathMode.REAL),
                        new SystemPromptResolver());
        // The @Value defaults are only injected by Spring; set sensible budgets for the unit test.
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
                null);
    }

    private static AgentRunner.LlmBinding binding(String systemPrompt) {
        return new AgentRunner.LlmBinding(
                ProviderType.DEEPSEEK,
                "stub-model",
                "stub-key",
                LlmOptions.defaults(),
                systemPrompt);
    }

    /** A caller that replays a fixed script of responses, in order. */
    private static UniformLLMCaller scripted(VetoResponse... responses) {
        ArrayDeque<VetoResponse> queue = new ArrayDeque<>(List.of(responses));
        return request -> {
            VetoResponse r = queue.poll();
            if (r == null) {
                throw new IllegalStateException("scripted caller exhausted");
            }
            return r;
        };
    }

    /**
     * A caller that replays a fixed main-loop script but routes the compactor (invoked inside a
     * delegation transform) to a fixed {@code "{}"} summary. The compactor shares this caller with
     * the main loop; without routing it, every transform would consume a main-script response. The
     * empty summary also means no {@link TurnType#COMPACTION_SUMMARY} turn is appended, keeping the
     * transform's appended sequence minimal for assertions.
     */
    private static UniformLLMCaller scriptedWithCompactor(VetoResponse... mainResponses) {
        ArrayDeque<VetoResponse> queue = new ArrayDeque<>(List.of(mainResponses));
        return request -> {
            if (request.systemPrompt().startsWith("Summarize the following conversation segment")) {
                return new VetoResponse(
                        null, List.of(), "{}", new VetoResponse.Features(false), null);
            }
            VetoResponse r = queue.poll();
            if (r == null) {
                throw new IllegalStateException("scripted caller exhausted");
            }
            return r;
        };
    }

    /**
     * Builds an {@link AgentService} with a custom tool engine for both dispatch + role filtering.
     */
    private static AgentService serviceWith(ToolEngine engine, UniformLLMCaller caller) {
        ObjectMapper mapper = new ObjectMapper();
        PromptCompiler compiler =
                new PromptCompiler(
                        new DefaultCapabilityTranslator(mapper),
                        Workspace.single(
                                Path.of(System.getProperty("user.dir", ".")), PathMode.REAL),
                        new SystemPromptResolver());
        ReflectionTestUtils.setField(compiler, "maxInputTokens", 32000);
        ReflectionTestUtils.setField(compiler, "contextFillRatio", 0.9);
        return new AgentService(
                engine,
                new HitlRegistry(),
                new IngressDefense(),
                compiler,
                caller,
                mapper,
                List.of(),
                new top.focess.veto.agent.identity.RoleToolFilter(engine),
                "REAL",
                50L,
                "FULL_ACCESS",
                "STRICT",
                null,
                null);
    }

    private static VetoResponse thoughtOn(String thought, String message) {
        return new VetoResponse(
                thought, List.of(), message, new VetoResponse.Features(false), null);
    }

    private static VetoResponse thoughtOnWithCall(String thought, String message, ToolCall call) {
        return new VetoResponse(
                thought, List.of(call), message, new VetoResponse.Features(false), null);
    }

    @Test
    void autonomousLoopFinishesAndStreamsMessage() throws Exception {
        AtomicReference<String> streamed = new AtomicReference<>();
        AgentService service = serviceWith(scripted(thoughtOn("2 + 2 = 4.", "The answer is 4.")));

        AgentResult result =
                service.submit(
                        "finish-test",
                        "What is 2 + 2?",
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT,
                        streamed::set);

        assertTrue(result.success(), "episode should finish successfully");
        assertEquals("The answer is 4.", result.message());
        assertEquals("The answer is 4.", streamed.get(), "the user-facing message was streamed");

        VetoAgent agent = service.agent("finish-test");
        assertNotNull(agent);
        assertReturnsToIdle(agent);

        List<TurnType> types = agent.history().stream().map(TurnRecord::type).toList();
        assertTrue(types.contains(TurnType.USER_PROMPT));
        assertTrue(types.contains(TurnType.ASSISTANT_RESPONSE));
        assertFalse(types.contains(TurnType.TOOL_CALL));
    }

    @Test
    void autonomousLoopExecutesToolCallThenFinishes() throws Exception {
        List<String> streamed = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        AgentService service =
                serviceWith(
                        scripted(
                                thoughtOnWithCall(
                                        "I'll compute 2+2 via calc.",
                                        "Let me check.",
                                        new ToolCall("calc", Map.of("expr", "2+2"))),
                                thoughtOn("calc says 4.", "The answer is 4.")));

        AgentResult result =
                service.submit(
                        "tool-test",
                        "What is 2 + 2?",
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT,
                        streamed::add);

        assertTrue(result.success(), "episode should finish successfully after the tool call");
        assertEquals("The answer is 4.", result.message());
        assertTrue(streamed.contains("Let me check."));
        assertTrue(streamed.contains("The answer is 4."));

        VetoAgent agent = service.agent("tool-test");
        List<TurnType> types = agent.history().stream().map(TurnRecord::type).toList();
        assertTrue(types.contains(TurnType.TOOL_CALL), "the tool call was recorded");
        assertTrue(types.contains(TurnType.TOOL_RESPONSE), "the tool observation was recorded");
        assertTrue(types.contains(TurnType.ASSISTANT_RESPONSE));
        assertReturnsToIdle(agent);
    }

    @Test
    void createGroupTransformsStandaloneIntoLeader() throws Exception {
        // The Leader binding the transform adopts - distinct model so the swap is observable.
        AgentRunner.LlmBinding leaderBinding =
                new AgentRunner.LlmBinding(
                        ProviderType.DEEPSEEK,
                        "leader-model",
                        "leader-key",
                        LlmOptions.defaults(),
                        "leader base");
        TransformToolEngine engine = new TransformToolEngine(leaderBinding, Set.of());
        AgentService service =
                serviceWith(
                        engine,
                        scriptedWithCompactor(
                                thoughtOnWithCall(
                                        "I'll delegate this.",
                                        "Spawning a group.",
                                        new ToolCall("create_group", Map.of("task", "ship it"))),
                                thoughtOn("Group is leading.", "Done.")));

        AgentResult result =
                service.submit(
                        "transform-fwd",
                        "Ship the feature.",
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT);

        assertTrue(result.success(), "episode should finish after the forward transform");
        VetoAgent agent = service.agent("transform-fwd");
        AgentRunner runner = (AgentRunner) ReflectionTestUtils.getField(agent, "runner");
        assertNotNull(runner, "runner is reachable from the VetoAgent");

        // The STANDALONE persona mutated to LEADER, the Leader binding applied, the group stamped.
        AgentPersona persona = (AgentPersona) ReflectionTestUtils.getField(runner, "persona");
        assertEquals(Role.LEADER, persona.role(), "persona role advanced to LEADER");
        assertEquals("leader-model", runner.binding().model(), "the Leader binding was applied");
        assertEquals(
                engine.lastDirective().groupId(),
                runner.groupId(),
                "the group id was stamped on the runner");

        // The transform appended a REWIND + AGENT_INIT(leader) + USER_PROMPT(brief) sequence.
        List<TurnRecord> history = agent.history();
        assertTrue(
                history.stream().anyMatch(t -> t.type() == TurnType.REWIND), "REWIND was recorded");
        assertTrue(
                history.stream()
                        .anyMatch(
                                t ->
                                        t.type() == TurnType.AGENT_INIT
                                                && "leader".equals(t.payload().get("content"))),
                "AGENT_INIT(leader) was recorded");
        assertTrue(
                history.stream()
                        .anyMatch(
                                t ->
                                        t.type() == TurnType.USER_PROMPT
                                                && "Lead the group and ship the feature."
                                                        .equals(t.payload().get("content"))),
                "the Leader brief was seeded as a user prompt");
        assertReturnsToIdle(agent);
    }

    @Test
    void disbandGroupReversesTransformBackToStandalone() throws Exception {
        AgentRunner.LlmBinding leaderBinding =
                new AgentRunner.LlmBinding(
                        ProviderType.DEEPSEEK,
                        "leader-model",
                        "leader-key",
                        LlmOptions.defaults(),
                        "leader base");
        TransformToolEngine engine = new TransformToolEngine(leaderBinding, Set.of());
        AgentService service =
                serviceWith(
                        engine,
                        scriptedWithCompactor(
                                thoughtOnWithCall(
                                        "I'll delegate this.",
                                        "Spawning a group.",
                                        new ToolCall("create_group", Map.of("task", "ship it"))),
                                thoughtOnWithCall(
                                        "Work is done.",
                                        "Disbanding.",
                                        new ToolCall("disband_group", Map.of())),
                                thoughtOn("Back to standalone.", "All done.")));

        AgentResult result =
                service.submit(
                        "transform-rev",
                        "Ship the feature.",
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT);

        assertTrue(result.success(), "episode should finish after the reverse transform");
        VetoAgent agent = service.agent("transform-rev");
        AgentRunner runner = (AgentRunner) ReflectionTestUtils.getField(agent, "runner");
        assertNotNull(runner, "runner is reachable from the VetoAgent");

        // The stashed STANDALONE persona + binding are restored and the group stamp cleared.
        AgentPersona persona = (AgentPersona) ReflectionTestUtils.getField(runner, "persona");
        assertEquals(Role.STANDALONE, persona.role(), "persona role restored to STANDALONE");
        assertEquals(
                "stub-model",
                runner.binding().model(),
                "the original STANDALONE binding was restored");
        assertNull(runner.groupId(), "the group stamp was cleared");

        // The reverse transform appended AGENT_INIT(standalone) + USER_PROMPT(disband brief).
        List<TurnRecord> history = agent.history();
        assertTrue(
                history.stream()
                        .anyMatch(
                                t ->
                                        t.type() == TurnType.AGENT_INIT
                                                && "standalone".equals(t.payload().get("content"))),
                "AGENT_INIT(standalone) was recorded");
        assertTrue(
                history.stream()
                        .anyMatch(
                                t ->
                                        t.type() == TurnType.USER_PROMPT
                                                && "Delegation complete: feature shipped."
                                                        .equals(t.payload().get("content"))),
                "the disband brief was seeded as a user prompt");
        assertReturnsToIdle(agent);
    }

    /**
     * A stub {@link ToolEngine} that drives the delegation transform the way the real {@code
     * GroupTools} do: {@code create_group} requests a forward transform (STANDALONE -> Leader) and
     * {@code disband_group} requests the reverse. {@link #resolveDefinition} returns an {@link
     * AgentToolDefinition} so the loop's screening short-circuits to AUTO_APPROVE (no HITL),
     * letting the call flow straight into the drain pass that applies the transform.
     */
    private static final class TransformToolEngine implements ToolEngine {
        private final AgentRunner.LlmBinding leaderBinding;
        private final Set<ToolDefinition> leaderTools;
        private final UUID groupId = UUID.randomUUID();
        private ToolCallContextHolder.TransformDirective lastDirective;

        TransformToolEngine(AgentRunner.LlmBinding leaderBinding, Set<ToolDefinition> leaderTools) {
            this.leaderBinding = leaderBinding;
            this.leaderTools = leaderTools;
        }

        ToolCallContextHolder.TransformDirective lastDirective() {
            return lastDirective;
        }

        @Override
        public @NonNull List<ToolDefinition> getActiveTools(@Nullable Set<String> whitelist) {
            return List.of();
        }

        @Override
        public @Nullable ToolDefinition resolveDefinition(@NonNull String toolName) {
            if ("create_group".equals(toolName) || "disband_group".equals(toolName)) {
                return new AgentToolDefinition(toolName, "transform stub", Void.class, Map.of());
            }
            return null;
        }

        @Override
        public @NonNull ToolResult execute(@NonNull ToolCall call, @NonNull ToolDefinition def) {
            if ("create_group".equals(call.toolName())) {
                ToolCallContextHolder.TransformDirective directive =
                        new ToolCallContextHolder.TransformDirective(
                                "Lead the group and ship the feature.",
                                groupId,
                                leaderBinding,
                                leaderTools);
                lastDirective = directive;
                ToolCallContextHolder.requestTransform(directive);
                return new ToolResult(call.toolName(), call.callId(), true, "");
            }
            if ("disband_group".equals(call.toolName())) {
                ToolCallContextHolder.requestReverseTransform(
                        "Delegation complete: feature shipped.");
                return new ToolResult(call.toolName(), call.callId(), true, "");
            }
            return new ToolResult(call.toolName(), call.callId(), false, "unknown tool");
        }
    }

    /**
     * Polls until the agent parks back in IDLE after its episode (the loop completes then parks).
     */
    private static void assertReturnsToIdle(VetoAgent agent) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (agent.state() != AgentState.IDLE && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(
                AgentState.IDLE, agent.state(), "agent should return to IDLE after the episode");
    }
}
