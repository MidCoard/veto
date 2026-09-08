package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.identity.RoleToolFilter;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.AgentToolDefinition;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.agent.tool.ToolResult;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.sandbox.SandboxManager;
import top.focess.veto.sandbox.TestSandboxFactory;

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
    private static @NonNull AgentService serviceWith(@NonNull UniformLLMCaller caller) {
        ObjectMapper mapper = new ObjectMapper();
        PromptCompiler compiler =
                new PromptCompiler(
                        new DefaultCapabilityTranslator(mapper),
                        new SystemPromptResolver(),
                        mapper,
                        "FULL_ACCESS");
        // Spring injects configuration in production; the unit test supplies explicit budgets.
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
                null,
                new BackgroundTaskManager(
                        new SandboxManager(TestSandboxFactory.uncontainedSubprocesses())));
    }

    private static AgentRunner.@NonNull LlmBinding binding(@NonNull String systemPrompt) {
        return new AgentRunner.LlmBinding(
                ProviderType.DEEPSEEK,
                "stub-model",
                "stub-key",
                LlmOptions.defaults(),
                systemPrompt);
    }

    /** A caller that replays a fixed script of responses, in order. */
    private static @NonNull UniformLLMCaller scripted(
            @NonNull VetoResponse @NonNull ... responses) {
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
    private static @NonNull UniformLLMCaller scriptedWithCompactor(
            @NonNull VetoResponse @NonNull ... mainResponses) {
        ArrayDeque<VetoResponse> queue = new ArrayDeque<>(List.of(mainResponses));
        return request -> {
            if (request.systemPrompt().startsWith("Summarize the following conversation segment")) {
                return new VetoResponse(null, null, "{}", null);
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
    private static @NonNull AgentService serviceWith(
            @NonNull ToolEngine engine, @NonNull UniformLLMCaller caller) {
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
                engine,
                new HitlRegistry(),
                new IngressDefense(),
                compiler,
                caller,
                mapper,
                List.of(),
                new RoleToolFilter(engine),
                "REAL",
                50L,
                1000,
                "FULL_ACCESS",
                "STRICT",
                null,
                null,
                new BackgroundTaskManager(
                        new SandboxManager(TestSandboxFactory.uncontainedSubprocesses())));
    }

    private static @NonNull VetoResponse thoughtOn(String thought, String message) {
        return new VetoResponse(thought, null, message, null);
    }

    private static @NonNull VetoResponse thoughtOnWithCall(
            String thought, String message, @NonNull ToolCall call) {
        return new VetoResponse(thought, List.of(call), message, null);
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

        VetoAgent agent = requireAgent(service.agent("finish-test"));
        assertReturnsToIdle(agent);

        List<TurnType> types = agent.history().stream().map(TurnRecord::type).toList();
        assertTrue(types.contains(TurnType.USER_PROMPT));
        assertTrue(types.contains(TurnType.ASSISTANT_RESPONSE));
        assertFalse(types.contains(TurnType.TOOL_CALL));
    }

    @Test
    void autonomousLoopExecutesToolCallThenFinishes() throws Exception {
        List<String> streamed = Collections.synchronizedList(new ArrayList<>());
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

        VetoAgent agent = requireAgent(service.agent("tool-test"));
        List<TurnType> types = agent.history().stream().map(TurnRecord::type).toList();
        assertTrue(types.contains(TurnType.TOOL_CALL), "the tool call was recorded");
        assertTrue(types.contains(TurnType.TOOL_RESPONSE), "the tool observation was recorded");
        assertTrue(types.contains(TurnType.ASSISTANT_RESPONSE));
        assertReturnsToIdle(agent);
    }

    /**
     * Regression contract: one tool call in the model's response must produce exactly one
     * TOOL_RESPONSE turn in history - not two. A prior bug (introduced when a recordRecentCall hook
     * was inserted between two identical appendTurn calls in executeOneConfirmedCall) doubled every
     * tool observation, wasting turn numbers and feeding the model duplicate observations.
     */
    @Test
    void oneToolCallProducesExactlyOneToolResponse() throws Exception {
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
                        "one-response-test",
                        "What is 2 + 2?",
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT,
                        ignored -> {});

        assertTrue(result.success(), "episode should finish successfully after the tool call");

        VetoAgent agent = requireAgent(service.agent("one-response-test"));
        long toolCalls =
                agent.history().stream().filter(t -> t.type() == TurnType.TOOL_CALL).count();
        long toolResponses =
                agent.history().stream().filter(t -> t.type() == TurnType.TOOL_RESPONSE).count();
        assertEquals(1, toolCalls, "exactly one TOOL_CALL turn should be recorded");
        assertEquals(
                1,
                toolResponses,
                "exactly one TOOL_RESPONSE turn should be recorded"
                        + " (regression: a duplicate appendTurn doubled this)");
    }

    @Test
    void turnNumbersAreStrictlyIncreasingAcrossThoughtAndToolCall() throws Exception {
        // Regression: a non-blank thought followed by a tool call used to reuse the user prompt's
        // turn_number (appendThought and the confirmed-path appendToolCall did not advance the
        // counter), so the second record collided on the uk_turn_records_agent_turn unique key and
        // the durable turn log rejected it, leaving the DB inconsistent with the in-memory history.
        // The runner now authoritatively allocates a unique, strictly-increasing number per
        // appended
        // record.
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
                        "turn-numbers-test",
                        "What is 2 + 2?",
                        binding("You are a helpful assistant."),
                        EPISODE_TIMEOUT,
                        ignored -> {});

        assertTrue(result.success(), "episode should finish successfully");
        VetoAgent agent = requireAgent(service.agent("turn-numbers-test"));
        List<Integer> numbers = agent.history().stream().map(TurnRecord::turnNumber).toList();
        assertFalse(numbers.isEmpty(), "history should contain turns");
        for (int i = 1; i < numbers.size(); i++) {
            assertTrue(
                    numbers.get(i) > numbers.get(i - 1),
                    "turn numbers must be strictly increasing; got " + numbers);
        }
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
        VetoAgent agent = requireAgent(service.agent("transform-fwd"));
        AgentRunner runner =
                assertInstanceOf(
                        ToolDocs.nonNullClass(AgentRunner.class),
                        requireField(ReflectionTestUtils.getField(agent, "runner")));

        // The STANDALONE persona mutated to LEADER, the Leader binding applied, the group stamped.
        AgentPersona persona =
                assertInstanceOf(
                        ToolDocs.nonNullClass(AgentPersona.class),
                        requireField(ReflectionTestUtils.getField(runner, "persona")));
        assertEquals(Role.LEADER, persona.role(), "persona role advanced to LEADER");
        assertEquals(Role.LEADER, agent.persona().role());
        assertEquals(runner.whitelistedToolsView(), agent.whitelistedTools());
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
                                                && "leader".equals(t.payload().get("role"))
                                                && t.payload().containsKey("system_prompt")),
                "AGENT_INIT(leader) was recorded");
        assertTrue(
                history.stream()
                        .anyMatch(
                                t ->
                                        t.type() == TurnType.USER_PROMPT
                                                && String.valueOf(t.payload().get("content"))
                                                        .contains(
                                                                "Lead the group and ship the feature.")
                                                && String.valueOf(t.payload().get("content"))
                                                        .contains("Ship the feature.")),
                "the original request survives the delegation brief");
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
        VetoAgent agent = requireAgent(service.agent("transform-rev"));
        AgentRunner runner =
                assertInstanceOf(
                        ToolDocs.nonNullClass(AgentRunner.class),
                        requireField(ReflectionTestUtils.getField(agent, "runner")));

        // The stashed STANDALONE persona + binding are restored and the group stamp cleared.
        AgentPersona persona =
                assertInstanceOf(
                        ToolDocs.nonNullClass(AgentPersona.class),
                        requireField(ReflectionTestUtils.getField(runner, "persona")));
        assertEquals(Role.STANDALONE, persona.role(), "persona role restored to STANDALONE");
        assertEquals(Role.STANDALONE, agent.persona().role());
        assertEquals(runner.whitelistedToolsView(), agent.whitelistedTools());
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
                                                && "standalone".equals(t.payload().get("role"))
                                                && t.payload().containsKey("system_prompt")),
                "AGENT_INIT(standalone) was recorded");
        assertTrue(
                history.stream()
                        .anyMatch(
                                t ->
                                        t.type() == TurnType.USER_PROMPT
                                                && String.valueOf(t.payload().get("content"))
                                                        .contains(
                                                                "Delegation complete: feature shipped.")
                                                && String.valueOf(t.payload().get("content"))
                                                        .contains("Ship the feature.")),
                "the disband brief was seeded as a user prompt");
        assertReturnsToIdle(agent);
    }

    @Test
    void mateUsesParentSessionBeforeItsFirstTurn() throws Exception {
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        var service = serviceWith(scripted(thoughtOn("Report", "Mate finished.")));
        service.getOrCreateAgent(
                sessionId.toString(),
                UUID.randomUUID().toString(),
                binding("Parent"),
                List.of(),
                userId,
                "owner",
                Path.of(".").toAbsolutePath().toString(),
                0,
                ToolResultPresentationMode.BASIC,
                false);
        var persona =
                new AgentPersona(
                        UUID.randomUUID().toString(),
                        "Mate",
                        "Worker",
                        Set.of(),
                        List.of(),
                        Role.MATE);
        var mate =
                service.createMate(
                        persona,
                        binding("Mate"),
                        userId,
                        "owner",
                        Workspace.single(Path.of("."), PathMode.REAL),
                        ToolResultPresentationMode.BASIC,
                        false,
                        sessionId);
        try {
            var runner =
                    assertInstanceOf(
                            ToolDocs.nonNullClass(AgentRunner.class),
                            requireField(ReflectionTestUtils.getField(mate, "runner")));
            assertEquals(sessionId, ReflectionTestUtils.getField(runner, "sessionId"));
            assertNotEquals(persona.id(), sessionId.toString());
            mate.submit("Execute assigned work");
            assertTrue(mate.await(EPISODE_TIMEOUT).success());
            assertFalse(mate.history().isEmpty());
            assertEquals(sessionId, ReflectionTestUtils.getField(runner, "sessionId"));
        } finally {
            service.remove(sessionId.toString());
        }
    }

    @Test
    void guidedTransformsContinueTheLoopAndDiscardTheOldProgram() throws Exception {
        var leaderBinding = binding("leader base");
        var engine =
                new TransformToolEngine(
                        leaderBinding, Set.of(transformDefinition("disband_group")));
        var mapper = new ObjectMapper();
        var forward =
                new VetoResponse(
                        null,
                        null,
                        null,
                        new VetoResponse.Guide(
                                mapper.readTree(
                                        """
                [{"id":"delegate","label":"Delegate","type":"tool","tool":"create_group","inputs":{"task":"ship it"},"outputs":{"stale":"content"}},
                 {"id":"stop","label":"Old stop","type":"STOP","result_binding":"stale"}]
                """)));
        var reverse =
                new VetoResponse(
                        null,
                        null,
                        null,
                        new VetoResponse.Guide(
                                mapper.readTree(
                                        """
                [{"id":"disband","label":"Disband","type":"tool","tool":"disband_group","inputs":{},"outputs":{"stale":"content"}},
                 {"id":"stop","label":"Old stop","type":"STOP","result_binding":"stale"}]
                """)));
        var service =
                serviceWith(
                        engine,
                        scriptedWithCompactor(
                                forward,
                                reverse,
                                thoughtOn("Finished", "Both transformations completed.")));
        service.getOrCreateAgent(
                "guided-transform",
                null,
                binding("standalone base"),
                List.of(),
                UUID.randomUUID(),
                null,
                null,
                0,
                ToolResultPresentationMode.BASIC,
                true);
        var result =
                service.submit(
                        "guided-transform",
                        "Ship the feature",
                        binding("standalone base"),
                        EPISODE_TIMEOUT);
        assertTrue(result.success(), result.message());
        assertEquals("Both transformations completed.", result.message());
        var agent = requireAgent(service.agent("guided-transform"));
        assertEquals(List.of("create_group", "disband_group"), engine.executed);
        assertEquals(2, agent.history().stream().filter(t -> t.type() == TurnType.REWIND).count());
        assertReturnsToIdle(agent);
    }

    @Test
    void transformationStopsCallsAuthoredForThePreviousRole() throws Exception {
        var engine = new TransformToolEngine(binding("leader base"), Set.of());
        var delegate = new ToolCall("create_group", Map.of("task", "ship it"));
        var service =
                serviceWith(
                        engine,
                        scriptedWithCompactor(
                                new VetoResponse(
                                        "Delegate",
                                        List.of(
                                                delegate,
                                                new ToolCall(
                                                        "create_group",
                                                        Map.of("task", "stale duplicate"))),
                                        null,
                                        null),
                                thoughtOn("Leading", "Leader continued.")));
        var result =
                service.submit(
                        "batch-transform", "Ship it", binding("standalone base"), EPISODE_TIMEOUT);
        assertTrue(result.success(), result.message());
        assertEquals("Leader continued.", result.message());
        assertEquals(
                List.of("create_group"),
                engine.executed,
                "Old-role calls must not execute after transformation");
        assertReturnsToIdle(requireAgent(service.agent("batch-transform")));
    }

    @SuppressWarnings("type.arguments.not.inferred")
    private static @NonNull ToolDefinition transformDefinition(@NonNull String name) {
        return new AgentToolDefinition(
                name,
                "transform stub",
                "create_group".equals(name)
                        ? ToolCapability.DELEGATION
                        : ToolCapability.GROUP_CONTROL,
                Danger.SAFE,
                Object.class,
                ToolDocs.nonNullClass(Void.class),
                Map.of());
    }

    /**
     * A stub {@link ToolEngine} that drives the delegation transform the way the real {@code
     * GroupTools} do: {@code create_group} requests a forward transform (STANDALONE -> Leader) and
     * {@code disband_group} requests the reverse. {@link #resolveDefinition} returns an {@link
     * AgentToolDefinition} so the loop's screening short-circuits to AUTO_APPROVE (no HITL),
     * letting the call flow straight into the drain pass that applies the transform.
     */
    private static final class TransformToolEngine implements ToolEngine {
        private final AgentRunner.@NonNull LlmBinding leaderBinding;
        private final @NonNull Set<ToolDefinition> leaderTools;
        private final @NonNull UUID groupId = UUID.randomUUID();
        private ToolCallContextHolder.TransformDirective lastDirective;
        private final List<String> executed = new ArrayList<>();

        TransformToolEngine(
                AgentRunner.@NonNull LlmBinding leaderBinding,
                @NonNull Set<ToolDefinition> leaderTools) {
            this.leaderBinding = leaderBinding;
            this.leaderTools = leaderTools;
        }

        ToolCallContextHolder.@NonNull TransformDirective lastDirective() {
            ToolCallContextHolder.TransformDirective directive = lastDirective;
            if (directive == null) throw new AssertionError("transform directive was not captured");
            return directive;
        }

        @Override
        public @NonNull List<ToolDefinition> getActiveTools(Set<String> whitelist) {
            return List.of(
                    transformDefinition("create_group"), transformDefinition("disband_group"));
        }

        @Override
        @SuppressWarnings("type.arguments.not.inferred")
        public ToolDefinition resolveDefinition(@NonNull String toolName) {
            if ("create_group".equals(toolName) || "disband_group".equals(toolName)) {
                return transformDefinition(toolName);
            }
            return null;
        }

        @Override
        public @NonNull ToolResult execute(@NonNull ToolCall call, @NonNull ToolDefinition def) {
            executed.add(call.toolName());
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
    private static void assertReturnsToIdle(@NonNull VetoAgent agent) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (agent.state() != AgentState.IDLE && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(
                AgentState.IDLE, agent.state(), "agent should return to IDLE after the episode");
    }

    private static @NonNull VetoAgent requireAgent(VetoAgent agent) {
        if (agent == null) throw new AssertionError("expected agent");
        return agent;
    }

    private static @NonNull Object requireField(Object value) {
        if (value == null) throw new AssertionError("expected reflected field");
        return value;
    }
}
