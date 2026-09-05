package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.identity.RoleToolFilter;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.NativeToolDefinition;
import top.focess.veto.agent.tool.ParamCategory;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.agent.tool.ToolResult;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.sandbox.SandboxManager;
import top.focess.veto.sandbox.TestSandboxFactory;

/**
 * Verifies the production agent persona resolves a real tool whitelist from the {@link ToolEngine}
 * (so the agent is advertised tools it can call). Previously {@code buildPersona} returned an empty
 * whitelist ({@code Set.of()}) — the agent was advertised ZERO tools and could call nothing.
 */
class PersonaToolWhitelistTest {

    private static final @NonNull Duration EPISODE_TIMEOUT = Duration.ofSeconds(10);

    /** A ToolEngine stub that advertises one native tool (read_file). */
    @SuppressWarnings("type.arguments.not.inferred")
    private static @NonNull ToolEngine engineWithReadFile() {
        NativeToolDefinition read =
                new NativeToolDefinition(
                        "read_file",
                        "Read a file",
                        ToolCapability.WORKSPACE_READ,
                        Danger.SAFE,
                        false,
                        ToolDocs.nonNullClass(Void.class),
                        Map.of("path", ParamCategory.FILESYSTEM_PATH));
        return new ToolEngine() {
            @Override
            public @NonNull List<ToolDefinition> getActiveTools(Set<String> whitelist) {
                return List.of(read);
            }

            @Override
            public ToolDefinition resolveDefinition(String toolName) {
                return null;
            }

            @Override
            public @NonNull ToolResult execute(
                    @NonNull ToolCall call, @NonNull ToolDefinition def) {
                return new ToolResult(call.toolName(), call.callId(), true, "");
            }
        };
    }

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

    private static AgentRunner.@NonNull LlmBinding binding() {
        return new AgentRunner.LlmBinding(
                ProviderType.DEEPSEEK, "stub-model", "stub-key", LlmOptions.defaults(), "sys");
    }

    @Test
    void productionAgentIsAdvertisedResolvedTools() throws Exception {
        List<VetoRequest> seen = new CopyOnWriteArrayList<>();
        UniformLLMCaller caller =
                request -> {
                    seen.add(request);
                    return new VetoResponse(
                            "done", List.of(), "ok", new VetoResponse.Features(false), null);
                };
        AgentService service = serviceWith(engineWithReadFile(), caller);
        service.submit("whitelist-test", "hi", binding(), EPISODE_TIMEOUT);

        assertFalse(seen.isEmpty(), "the model was called");
        assertFalse(
                seen.get(0).tools().isEmpty(), "the agent must be advertised its resolved tools");
        assertEquals(
                "read_file",
                seen.get(0).tools().get(0).name(),
                "the advertised tool is the engine's native tool");
    }

    @Test
    void emptyEngineAdvertisesNoTools() {
        // An empty engine means no tools are advertised.
        List<VetoRequest> seen = new CopyOnWriteArrayList<>();
        UniformLLMCaller caller =
                request -> {
                    seen.add(request);
                    return new VetoResponse(
                            "done", List.of(), "ok", new VetoResponse.Features(false), null);
                };
        AgentService service = serviceWith(new TestToolEngine(), caller);
        assertDoesNotThrow(() -> service.submit("empty-test", "hi", binding(), EPISODE_TIMEOUT));
        assertFalse(seen.isEmpty());
        assertTrue(seen.get(0).tools().isEmpty(), "an empty engine advertises no tools");
    }
}
