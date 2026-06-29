package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.mcp.DefaultMcpEngine;
import top.focess.veto.agent.mcp.McpEngine;
import top.focess.veto.agent.mcp.McpToolResult;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.core.VetoResponse;

/**
 * Verifies the production agent persona resolves a real tool whitelist from the {@link McpEngine}
 * (so the agent is advertised tools it can call). Previously {@code buildPersona} returned an empty
 * whitelist ({@code Set.of()}) — the agent was advertised ZERO tools and could call nothing.
 */
class PersonaToolWhitelistTest {

    private static final Duration EPISODE_TIMEOUT = Duration.ofSeconds(10);

    /** A McpEngine stub that advertises one native tool (read_file). */
    private static McpEngine engineWithReadFile() {
        NativeToolDefinition read =
                new NativeToolDefinition(
                        "read_file",
                        "Read a file",
                        RiskCategory.READ_ONLY,
                        false,
                        Void.class,
                        Map.of("path", ParamCategory.FILESYSTEM_PATH));
        return new McpEngine() {
            @Override
            public List<ToolDefinition> getActiveTools(java.util.Set<String> whitelist) {
                return List.of(read);
            }

            @Override
            public ToolDefinition resolveDefinition(String toolName) {
                return null;
            }

            @Override
            public McpToolResult execute(ToolCall call, ToolDefinition def) {
                return new McpToolResult(call.toolName(), call.callId(), true, "");
            }
        };
    }

    private static AgentService serviceWith(McpEngine engine, UniformLLMCaller caller) {
        ObjectMapper mapper = new ObjectMapper();
        PromptCompiler compiler =
                new PromptCompiler(
                        new DefaultCapabilityTranslator(mapper),
                        Workspace.single(
                                Path.of(System.getProperty("user.dir", ".")), PathMode.REAL));
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
                System.getProperty("user.dir", "."),
                "",
                "REAL",
                50L,
                "FULL_ACCESS",
                "STRICT",
                null,
                null);
    }

    private static AgentRunner.LlmBinding binding() {
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
                            "done",
                            List.of(),
                            "ok",
                            true,
                            new VetoResponse.Features(false, true),
                            null);
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
        // A no-op engine (DefaultMcpEngine returns empty) → no tools advertised (no regression).
        List<VetoRequest> seen = new CopyOnWriteArrayList<>();
        UniformLLMCaller caller =
                request -> {
                    seen.add(request);
                    return new VetoResponse(
                            "done",
                            List.of(),
                            "ok",
                            true,
                            new VetoResponse.Features(false, true),
                            null);
                };
        AgentService service = serviceWith(new DefaultMcpEngine(), caller);
        assertDoesNotThrow(() -> service.submit("empty-test", "hi", binding(), EPISODE_TIMEOUT));
        assertFalse(seen.isEmpty());
        assertTrue(seen.get(0).tools().isEmpty(), "an empty engine advertises no tools");
    }
}
