package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.identity.SystemPromptResolver;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.intercept.InterceptResolution;
import top.focess.veto.agent.intercept.VetoOption;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolEngine;
import top.focess.veto.agent.mcp.ToolResult;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.screening.DangerComputation;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.translation.DefaultCapabilityTranslator;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.llm.exceptions.LlmException;

@SuppressWarnings("initialization.field.uninitialized")
class NewRequirementsTest {

    @TempDir @NonNull Path root;

    @BeforeEach
    void canonicalizeRoot() throws Exception {
        root = root.toRealPath();
    }

    private @NonNull NativeToolDefinition execDef() {
        return new NativeToolDefinition(
                "run_command",
                "exec",
                RiskCategory.SHELL_EXEC,
                false,
                ToolDocs.nonNullClass(ExecArgs.class),
                Map.of());
    }

    private @NonNull NativeToolDefinition readDef() {
        return new NativeToolDefinition(
                "view_file",
                "read",
                RiskCategory.READ_ONLY,
                false,
                ToolDocs.nonNullClass(ReadArgs.class),
                Map.of("path", ParamCategory.FILESYSTEM_PATH));
    }

    public record ExecArgs(Map<String, Object> commands, String cwd) {}

    public record ReadArgs(String path) {}

    private static class TestToolEngine implements ToolEngine {
        private final @NonNull Map<String, ToolDefinition> tools = new java.util.HashMap<>();

        public void register(@NonNull ToolDefinition def) {
            tools.put(def.name(), def);
        }

        @Override
        public @NonNull List<ToolDefinition> getActiveTools(Set<String> whitelist) {
            return new ArrayList<>(tools.values());
        }

        @Override
        public ToolDefinition resolveDefinition(@NonNull String toolName) {
            return tools.get(toolName);
        }

        @Override
        public @NonNull ToolResult execute(@NonNull ToolCall call, @NonNull ToolDefinition def) {
            return new ToolResult(call.toolName(), call.callId(), true, "success");
        }
    }

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

    private static @NonNull VetoResponse thoughtOnWithCall(
            String thought, String message, @NonNull ToolCall call) {
        return new VetoResponse(
                thought, List.of(call), message, new VetoResponse.Features(false), null);
    }

    @Test
    void refusedHoldsBatchAndDisplaysNotice() throws Exception {
        TestToolEngine mcpEngine = new TestToolEngine();
        mcpEngine.register(execDef());

        AtomicReference<String> streamedMessage = new AtomicReference<>();
        HitlRegistry hitlRegistry = new HitlRegistry();

        ToolCall ncCall =
                new ToolCall(
                        "run_command",
                        Map.of(
                                "commands",
                                        List.of(Map.of("executable", "nc", "args", List.of("-l"))),
                                "cwd", root.toString()),
                        "call-nc");

        UniformLLMCaller caller =
                scripted(thoughtOnWithCall("I will start netcat.", "Starting...", ncCall));

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
                        mcpEngine,
                        hitlRegistry,
                        new IngressDefense(),
                        compiler,
                        caller,
                        mapper,
                        List.of(),
                        new top.focess.veto.agent.identity.RoleToolFilter(mcpEngine),
                        "REAL",
                        50L,
                        "FULL_ACCESS",
                        "STRICT",
                        null,
                        null,
                        new top.focess.veto.sandbox.BackgroundTaskManager(
                                new top.focess.veto.sandbox.SandboxManager(
                                        new top.focess.veto.sandbox
                                                .ConstrainedSubprocessSubstrate())));

        String agentKey = "refused-test";
        CompletableFuture<AgentResult> resultFuture =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return service.submit(
                                        agentKey,
                                        "Run netcat",
                                        new AgentRunner.LlmBinding(
                                                ProviderType.DEEPSEEK,
                                                "stub",
                                                "key",
                                                LlmOptions.defaults(),
                                                "system"),
                                        Duration.ofSeconds(10),
                                        msg -> {
                                            System.out.println(
                                                    "TEST DEBUG: streamedMessage set called with message: "
                                                            + msg);
                                            streamedMessage.set(msg);
                                        });
                            } catch (Exception e) {
                                System.out.println("TEST DEBUG: exception in submit thread: " + e);
                                e.printStackTrace();
                                throw new RuntimeException(e);
                            }
                        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        VetoAgent agent = null;
        while (System.nanoTime() < deadline) {
            agent = service.agent(agentKey);
            if (agent != null && agent.state() == AgentState.INTERCEPTED) {
                System.out.println("TEST DEBUG: found agent in INTERCEPTED state");
                break;
            }
            Thread.sleep(10);
        }

        if (agent == null) throw new AssertionError("agent should not be null");
        System.out.println("TEST DEBUG: agent final checked state: " + agent.state());
        System.out.println("TEST DEBUG: agent history: " + agent.history());
        System.out.println("TEST DEBUG: streamedMessage current value: " + streamedMessage.get());

        assertEquals(AgentState.INTERCEPTED, agent.state());
        String streamed = streamedMessage.get();
        if (streamed == null) throw new AssertionError("streamed message should not be null");
        assertTrue(streamed.contains("CRITICAL"));

        // Resolve the HITL hold (retrying in case of race with register)
        boolean resolved = false;
        long resolveDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String resolvedAgentId = null;
        String resolvedCallId = null;
        while (System.nanoTime() < resolveDeadline) {
            Object pendingValue = ReflectionTestUtils.getField(hitlRegistry, "pending");
            if (pendingValue instanceof java.util.Map<?, ?> pending && !pending.isEmpty()) {
                for (Object pendingKey : pending.keySet()) {
                    if (!(pendingKey instanceof String key)) {
                        continue;
                    }
                    int idx = key.indexOf('|');
                    if (idx > 0) {
                        resolvedAgentId = key.substring(0, idx);
                        resolvedCallId = key.substring(idx + 1);
                        break;
                    }
                }
            }
            if (resolvedAgentId != null && resolvedCallId != null) {
                if (hitlRegistry.resolve(
                        resolvedAgentId,
                        resolvedCallId,
                        new InterceptResolution(VetoOption.EXEC_DECLINE, null))) {
                    resolved = true;
                    break;
                }
            }
            Thread.sleep(10);
        }
        assertTrue(resolved, "veto should be successfully resolved");

        AgentResult result = resultFuture.get(5, TimeUnit.SECONDS);
        assertFalse(result.success());
        assertEquals(AgentState.IDLE, agent.state());
    }

    @Test
    void fullAccessPermitsSensitivePathsAsDangerous() throws Exception {
        DangerComputation dc = new DangerComputation();
        Workspace ws = Workspace.single(root, PathMode.REAL);

        ToolCall callSsh =
                new ToolCall("view_file", Map.of("path", root.resolve(".ssh/id_rsa").toString()));
        Danger dangerSsh =
                dc.compute(
                        readDef(), callSsh, ws, DeployerPolicy.FULL_ACCESS, ProtectedSet.empty());
        assertEquals(Danger.DANGEROUS, dangerSsh);

        String devPath =
                System.getProperty("os.name").toLowerCase().contains("win")
                        ? "\\\\.\\PhysicalDrive0"
                        : "/dev/sda";
        ToolCall callDev = new ToolCall("view_file", Map.of("path", devPath));
        Danger dangerDev =
                dc.compute(
                        readDef(), callDev, ws, DeployerPolicy.FULL_ACCESS, ProtectedSet.empty());
        assertEquals(Danger.DANGEROUS, dangerDev);
    }

    @Test
    void nativeBridgeDisconnectTriggersIdle() throws Exception {
        TestToolEngine mcpEngine = new TestToolEngine();

        UniformLLMCaller caller =
                request -> {
                    throw new LlmException(
                            "Local SLM process disconnected unexpectedly.", false) {};
                };

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
                        mcpEngine,
                        new HitlRegistry(),
                        new IngressDefense(),
                        compiler,
                        caller,
                        mapper,
                        List.of(),
                        new top.focess.veto.agent.identity.RoleToolFilter(mcpEngine),
                        "REAL",
                        50L,
                        "FULL_ACCESS",
                        "STRICT",
                        null,
                        null,
                        new top.focess.veto.sandbox.BackgroundTaskManager(
                                new top.focess.veto.sandbox.SandboxManager(
                                        new top.focess.veto.sandbox
                                                .ConstrainedSubprocessSubstrate())));

        String agentKey = "disconnect-test";
        AgentResult result =
                service.submit(
                        agentKey,
                        "Hello assistant",
                        new AgentRunner.LlmBinding(
                                ProviderType.DEEPSEEK,
                                "stub",
                                "key",
                                LlmOptions.defaults(),
                                "system"),
                        Duration.ofSeconds(2),
                        msg -> {});

        assertFalse(result.success());
        VetoAgent agent = service.agent(agentKey);
        if (agent == null) throw new AssertionError("expected agent");
        assertEquals(AgentState.IDLE, agent.state());
    }
}
