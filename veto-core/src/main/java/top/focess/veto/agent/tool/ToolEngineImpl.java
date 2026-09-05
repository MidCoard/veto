package top.focess.veto.agent.tool;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.capability.RemoteCallCapability;
import top.focess.veto.agent.capability.RemoteCallCapabilityImpl;
import top.focess.veto.agent.mcp.transport.McpJsonRpcClient;
import top.focess.veto.agent.mcp.transport.McpTransport;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.sandbox.SandboxSubstrate;
import top.focess.veto.util.Nullness;

/**
 * The tool engine implementation — manages server registrations, schema discovery, and tool
 * dispatching. The loop calls {@link ToolEngine}'s three loop-facing methods.
 *
 * <p>Dispatch by definition flavour:
 *
 * <ul>
 *   <li><b>Native</b> — typed dispatch after capability validation. Workspace capabilities use
 *       Gateway-authorized canonical targets; process tools route through {@link SandboxSubstrate}
 *       or the background-task service.
 *   <li><b>Agent</b> — bean dispatch via typed invocation binding. Each agent tool is a
 *       self-contained {@link AgentTool} bean — just like native tools are self-contained {@link
 *       NativeTool} beans.
 *   <li><b>External</b> — forwarded over the registered {@link McpTransport}.
 * </ul>
 *
 * <p>Remote tools and their transports are registered together through {@link
 * #discoverAndRegister(McpTransport)}.
 */
@Service
public class ToolEngineImpl implements ToolEngine, SmartInitializingSingleton {

    private static final int MAX_TOOL_RESULT_CHARS = 1_000_000;

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.tool.ToolEngineImpl");

    private final @NonNull ObjectMapper mapper;
    private final @NonNull McpJsonRpcClient remoteClient;
    private final @NonNull List<NativeTool<?>> nativeToolBeans;
    private final @NonNull ApplicationContext applicationContext;

    private final @NonNull Map<String, NativeToolDefinition> nativeDefs = new ConcurrentHashMap<>();
    private final @NonNull Map<String, NativeTool<?>> nativeByName = new ConcurrentHashMap<>();
    private final @NonNull Map<String, AgentToolDefinition> agentDefs = new ConcurrentHashMap<>();
    private final @NonNull Map<String, AgentTool<?>> agentBeans = new LinkedHashMap<>();
    private final @NonNull Map<String, RemoteToolDefinition> remoteDefs = new ConcurrentHashMap<>();
    private final @NonNull Map<String, RemoteCallCapability> remoteCapabilities =
            new ConcurrentHashMap<>();

    public ToolEngineImpl(
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NonNull ObjectMapper mapper,
            @NonNull List<NativeTool<?>> nativeToolBeans,
            @NonNull ApplicationContext applicationContext) {
        this.mapper = mapper;
        this.remoteClient = new McpJsonRpcClient(mapper);
        this.nativeToolBeans = nativeToolBeans;
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        init();
    }

    /**
     * Delayed until every singleton exists so agent tools with a dependency back to ToolEngine are
     * visible.
     */
    void init() {
        // Register native tools
        for (NativeTool<?> bean : nativeToolBeans) {
            NativeToolDefinition def = ToolSchemaCompiler.compileNative(bean);
            ToolContractValidator.validateHandler(bean, def);
            ensureUniqueName(def.name());
            nativeDefs.put(def.name(), def);
            nativeByName.put(def.name(), bean);
            log.info("ToolEngine: registered native tool '{}'.", def.name());
        }

        // Discover and register agent tools via Spring
        for (AgentTool<?> bean : applicationContext.getBeansOfType(AgentTool.class).values()) {
            String toolName = bean.getName();
            AgentToolDefinition def =
                    AgentToolDefinition.from(toolName, bean.getArgsClass(), bean.getCapability());
            ToolContractValidator.validateHandler(bean, def);
            ensureUniqueName(def.name());
            agentDefs.put(def.name(), def);
            agentBeans.put(toolName, bean);
            log.info("ToolEngine: registered agent tool '{}'.", def.name());
        }

        log.info(
                "ToolEngine: initialized. {} native tool(s), {} agent tool(s).",
                nativeDefs.size(),
                agentDefs.size());
    }

    /** Discover tools from a remote MCP server via JSON-RPC tools/list and register them. */
    public synchronized @NonNull List<RemoteToolDefinition> discoverAndRegister(
            @NonNull McpTransport transport) {
        if (!(transport instanceof McpTransport.SseMcpTransport remote)) {
            throw new IllegalArgumentException(
                    "This MCP transport has no enforced execution boundary and cannot be registered.");
        }
        try {
            List<RemoteToolDefinition> tools = remoteClient.discoverTools(transport);
            Set<String> discoveredNames = new HashSet<>();
            for (RemoteToolDefinition t : tools) {
                ToolContractValidator.validate(t);
                if (!discoveredNames.add(t.name())) {
                    throw new IllegalArgumentException(
                            "Remote discovery returned duplicate tool name: " + t.name());
                }
                ensureUniqueName(t.name());
            }
            for (RemoteToolDefinition t : tools) {
                remoteCapabilities.put(
                        t.name(), new RemoteCallCapabilityImpl(t, remote, remoteClient));
                remoteDefs.put(t.name(), t);
            }
            log.info("ToolEngine: discovered {} remote tool(s).", tools.size());
            return tools;
        } catch (IOException e) {
            log.warn("ToolEngine: tools/list discovery failed ({})", e.getClass().getSimpleName());
            return List.of();
        }
    }

    @Override
    public @NonNull List<ToolDefinition> getActiveTools(Set<String> whitelist) {
        List<ToolDefinition> active = new ArrayList<>();
        for (NativeToolDefinition def : nativeDefs.values()) {
            if (whitelist == null || whitelist.contains(def.name())) {
                active.add(def);
            }
        }
        active.addAll(agentDefs.values()); // agent tools are always-on (included in every agent's
        // manifest via AgentService.buildPersona)
        for (RemoteToolDefinition def : remoteDefs.values()) {
            if (whitelist == null || whitelist.contains(def.name())) {
                active.add(def);
            }
        }
        return active;
    }

    @Override
    public ToolDefinition resolveDefinition(@NonNull String toolName) {
        ToolDefinition def = nativeDefs.get(toolName);
        if (def != null) return def;
        def = agentDefs.get(toolName);
        if (def != null) return def;
        return remoteDefs.get(toolName);
    }

    @Override
    public @NonNull ToolResult execute(@NonNull ToolCall call, @NonNull ToolDefinition def) {
        String callId = call.callId();
        ToolCallContextHolder.setCurrentCallId(callId);
        try {
            if (def != resolveDefinition(call.toolName())) {
                throw new SecurityException(
                        "Tool definition does not match the registered tool: " + call.toolName());
            }
            ToolResult result =
                    switch (def) {
                        case NativeToolDefinition nativeDef -> executeNative(call, nativeDef);
                        case AgentToolDefinition agentDef -> executeAgent(call, agentDef);
                        case RemoteToolDefinition remoteDef -> executeRemote(call, remoteDef);
                    };
            return boundResult(result);
        } catch (ToolExecutionException e) {
            return new ToolResult(
                    call.toolName(), callId, e.status(), e.format(), e.content(), e.errorCode());
        } catch (Exception e) {
            log.warn("Tool '{}' execution failed.", call.toolName(), e);
            return new ToolResult(
                    call.toolName(),
                    callId,
                    false,
                    "Tool execution failed: " + ToolErrors.normalize(e.getMessage()));
        } finally {
            ToolCallContextHolder.setCurrentCallId("");
        }
    }

    private static @NonNull ToolResult boundResult(@NonNull ToolResult result) {
        if (result.content().length() <= MAX_TOOL_RESULT_CHARS) {
            return result;
        }
        if (result.format() == ToolResultFormat.JSON) {
            return new ToolResult(
                    result.toolName(),
                    result.callId(),
                    ToolResultStatus.FAILURE,
                    ToolResultFormat.PLAINTEXT,
                    "Tool completed but its JSON result exceeds the output limit; result omitted.",
                    "TOOL_RESULT_TOO_LARGE");
        }
        String bounded =
                result.content().substring(0, MAX_TOOL_RESULT_CHARS)
                        + "\n[tool output truncated at "
                        + MAX_TOOL_RESULT_CHARS
                        + " chars]";
        return result.withContent(bounded);
    }

    // ── Implementation-detail API (not on the shared interface) ──────────────

    private void ensureUniqueName(@NonNull String name) {
        if (nativeDefs.containsKey(name)
                || agentDefs.containsKey(name)
                || remoteDefs.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate tool name: " + name);
        }
    }

    // ── Flavour dispatch ───────────────────────────────────────────────────────

    private @NonNull ToolResult executeNative(
            @NonNull ToolCall call, @NonNull NativeToolDefinition def) throws Exception {
        JsonNode jsonArgs = mapper.valueToTree(call.args());
        NativeToolArgumentValidator.validate(def.name(), jsonArgs, def.argsClass());
        requirePermit(call, def);
        NativeTool<?> bean = nativeByName.get(def.name());
        if (bean == null) {
            return new ToolResult(
                    call.toolName(),
                    call.callId(),
                    false,
                    "No bean for native tool: " + def.name());
        }
        String result = executeLocal(bean, jsonArgs);
        return successfulResult(call, def, result);
    }

    private <T> @NonNull String executeLocal(
            @NonNull CapabilityTool<T> tool, @NonNull JsonNode jsonArgs) throws Exception {
        T args = mapper.treeToValue(jsonArgs, tool.getArgsClass());
        return tool.execute(Nullness.requireNonNull(args, "Tool arguments deserialized to null"));
    }

    private @NonNull ToolResult executeAgent(
            @NonNull ToolCall call, @NonNull AgentToolDefinition def) {
        AgentTool<?> bean = agentBeans.get(def.name());
        if (bean == null) {
            return new ToolResult(
                    call.toolName(), call.callId(), false, "Unknown agent tool: " + def.name());
        }
        try {
            JsonNode jsonArgs = mapper.valueToTree(call.args());
            NativeToolArgumentValidator.validate(def.name(), jsonArgs, def.argsClass());
            requirePermit(call, def);
            String result = executeLocal(bean, jsonArgs);
            return successfulResult(call, def, result);
        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            return new ToolResult(
                    call.toolName(),
                    call.callId(),
                    false,
                    "Agent tool error: " + ToolErrors.normalize(e.getMessage()));
        }
    }

    /** External tool execution over the transport recorded during MCP discovery. */
    private @NonNull ToolResult executeRemote(
            @NonNull ToolCall call, @NonNull RemoteToolDefinition def) throws IOException {
        requirePermit(call, def);
        RemoteCallCapability capability = remoteCapabilities.get(def.name());
        if (capability == null) {
            throw new SecurityException(
                    "No restricted execution capability is registered for this remote tool.");
        }
        JsonNode result = capability.call(call);
        boolean success = !result.path("isError").asBoolean(false);
        String content = remoteContent(result);
        return new ToolResult(
                call.toolName(),
                call.callId(),
                success ? ToolResultStatus.SUCCESS : ToolResultStatus.FAILURE,
                ToolResultFormat.UNKNOWN,
                content,
                success ? null : "REMOTE_TOOL_FAILED");
    }

    private static @NonNull ToolCallContext requirePermit(
            @NonNull ToolCall call, @NonNull ToolDefinition definition) {
        ToolCallContext context = ToolCallContextHolder.get();
        if (context == null) {
            throw new SecurityException(
                    "This tool call is not authorized for the current session; submit a fresh call.");
        }
        if (!context.executionPermit().authorizes(call, definition, context)) {
            throw new SecurityException(
                    "This tool call is not authorized for the current session; submit a fresh call.");
        }
        if (definition.capability() == ToolCapability.AGENT_CONTROL) {
            throw new SecurityException("This tool is unavailable; use another available tool.");
        }
        return context;
    }

    private static @NonNull ToolResultFormat declaredFormat(@NonNull ToolDefinition definition) {
        return definition.resultFormats().size() == 1
                ? definition.resultFormats().iterator().next()
                : ToolResultFormat.UNKNOWN;
    }

    static @NonNull String remoteContent(@NonNull JsonNode result) {
        JsonNode blocks = result.get("content");
        if (blocks == null || !blocks.isArray()) {
            return result.toString();
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : blocks) {
            if (!"text".equals(block.path("type").asText()) || !block.has("text")) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(block.path("text").asText());
        }
        if (!text.isEmpty()) {
            return text.toString();
        }
        if (result.path("isError").asBoolean(false)) {
            return "Remote MCP tool reported isError=true with empty text content; raw result: "
                    + result;
        }
        return result.toString();
    }

    /** Enforces unambiguous JSON-only success contracts at the execution boundary. */
    private void validateSuccessfulResult(
            @NonNull ToolDefinition definition, @NonNull String result) {
        boolean json = definition.resultFormats().contains(ToolResultFormat.JSON);
        boolean plaintext = definition.resultFormats().contains(ToolResultFormat.PLAINTEXT);
        if (!json || plaintext) {
            return;
        }
        try {
            JsonNode parsed =
                    mapper.reader()
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                            .readTree(result);
            if (parsed == null || parsed.isMissingNode()) {
                ToolErrors.failure(
                        "Tool '"
                                + definition.name()
                                + "' declared json but returned no JSON value");
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ToolExecutionException toolFailure) {
                throw toolFailure;
            }
            ToolErrors.failure(
                    "Tool '" + definition.name() + "' declared json but returned invalid JSON");
        }
    }

    private @NonNull ToolResult successfulResult(
            @NonNull ToolCall call, @NonNull ToolDefinition definition, @NonNull String content) {
        validateSuccessfulResult(definition, content);
        return new ToolResult(
                call.toolName(),
                call.callId(),
                ToolResultStatus.SUCCESS,
                declaredFormat(definition),
                content,
                null);
    }
}
