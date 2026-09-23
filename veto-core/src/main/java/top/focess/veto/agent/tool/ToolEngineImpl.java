package top.focess.veto.agent.tool;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.capability.RemoteCallCapability;
import top.focess.veto.agent.capability.RemoteCallCapabilityImpl;
import top.focess.veto.agent.mcp.transport.McpJsonRpcClient;
import top.focess.veto.agent.mcp.transport.McpTransport;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.Cancellation;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contract.Tool;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.plugin.runtime.PluginJson;
import top.focess.veto.plugin.runtime.PluginManager;
import top.focess.veto.plugin.runtime.PluginSchema;
import top.focess.veto.plugin.runtime.SessionPlugins;
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
    private final ApplicationContext applicationContext;

    // One volatile publication binds every definition and implementation in a complete snapshot.
    private volatile @NonNull ToolCatalog catalog = ToolCatalog.empty();
    private boolean initialized;
    // Injected after construction and read from worker threads; volatile for safe publication.
    private volatile @Nullable SessionPlugins sessionPlugins;

    @Autowired
    public void attachSessionPlugins(@NonNull SessionPlugins value) {
        sessionPlugins = value;
    }

    @Autowired
    public ToolEngineImpl(
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NonNull ObjectMapper mapper,
            @NonNull List<NativeTool<?>> nativeToolBeans,
            @NonNull ApplicationContext applicationContext) {
        this.mapper = mapper;
        this.remoteClient = new McpJsonRpcClient(mapper);
        this.nativeToolBeans = nativeToolBeans;
        this.applicationContext = applicationContext;
    }

    private ToolEngineImpl(@NonNull ObjectMapper mapper, @NonNull List<NativeTool<?>> tools) {
        this.mapper = mapper;
        this.remoteClient = new McpJsonRpcClient(mapper);
        this.nativeToolBeans = List.copyOf(tools);
        this.applicationContext = null;
    }

    /** Registers only the supplied handlers through the normal contract validation path. */
    public static @NonNull ToolEngineImpl isolated(
            @NonNull ObjectMapper mapper, @NonNull List<NativeTool<?>> tools) {
        ToolEngineImpl engine = new ToolEngineImpl(mapper, tools);
        engine.init();
        return engine;
    }

    @Override
    public void afterSingletonsInstantiated() {
        init();
    }

    /**
     * Delayed until every singleton exists so agent tools with a dependency back to ToolEngine are
     * visible.
     */
    synchronized void init() {
        if (initialized) throw new IllegalStateException("Tool engine already initialized");
        List<RegisteredTool> staged = new ArrayList<>();
        for (NativeTool<?> bean : nativeToolBeans) {
            staged.add(ToolRegistration.local(bean, bean.getName(), null));
        }
        ApplicationContext context = applicationContext;
        if (context != null) {
            for (AgentTool<?> bean : context.getBeansOfType(AgentTool.class).values()) {
                staged.add(ToolRegistration.local(bean, bean.getName(), null));
            }
        }
        if (context != null) {
            for (var manager : context.getBeansOfType(PluginManager.class).values()) {
                for (var entry : manager.catalog().entries(StandardContributionPoints.TOOLS)) {
                    var plugin = manager.plugin(entry.source().namespace());
                    Tool descriptor = entry.implementation();
                    RemoteToolDefinition definition =
                            ToolSchemaCompiler.compilePluginScript(
                                    descriptor,
                                    manager.toolName(entry),
                                    PluginJson.toNode(descriptor.inputSchema()),
                                    plugin.bindingId(),
                                    plugin.identity().id(),
                                    plugin.identity().version());
                    staged.add(new RegisteredTool.Plugin(definition, descriptor, plugin));
                }
                for (var entry :
                        manager.catalog().entries(StandardContributionPoints.NATIVE_TOOLS)) {
                    var plugin = manager.plugin(entry.source().namespace());
                    CapabilityTool<?> tool = entry.implementation();
                    staged.add(
                            ToolRegistration.local(
                                    tool,
                                    manager.toolName(
                                            entry.source().namespace(), entry.id().value()),
                                    plugin));
                }
            }
        }
        // Validation and construction complete before readers can observe any new registration.
        catalog = catalog.append(staged);
        initialized = true;
        log.info("ToolEngine: published {} tools.", staged.size());
    }

    /** Discover tools from a remote MCP server via JSON-RPC tools/list and register them. */
    public synchronized @NonNull List<RemoteToolDefinition> discoverAndRegister(
            @NonNull McpTransport transport) {
        if (!(transport instanceof McpTransport.SseMcpTransport remote)) {
            throw new IllegalArgumentException(
                    "This MCP transport has no enforced execution boundary and cannot be"
                            + " registered.");
        }
        try {
            List<RemoteToolDefinition> tools = remoteClient.discoverTools(transport);
            List<RegisteredTool> staged = new ArrayList<>();
            for (RemoteToolDefinition definition : tools) {
                staged.add(
                        new RegisteredTool.Remote(
                                definition,
                                new RemoteCallCapabilityImpl(definition, remote, remoteClient)));
            }
            catalog = catalog.append(staged);
            log.info("ToolEngine: discovered {} remote tool(s).", tools.size());
            return tools;
        } catch (IOException e) {
            log.warn("ToolEngine: tools/list discovery failed ({})", e.getClass().getSimpleName());
            return List.of();
        }
    }

    @Override
    public @NonNull List<ToolDefinition> getActiveTools(Set<String> whitelist) {
        return catalog.active(whitelist);
    }

    @Override
    public ToolDefinition resolveDefinition(@NonNull String toolName) {
        RegisteredTool registration = catalog.resolve(toolName);
        return registration == null ? null : registration.definition();
    }

    /** Snapshot sequence for diagnostics and registration consistency tests. */
    long catalogGeneration() {
        return catalog.generation();
    }

    @Override
    public @NonNull ToolResult execute(@NonNull ToolCall call, @NonNull ToolDefinition def) {
        String callId = call.callId();
        ToolCallContextHolder.setCurrentCallId(callId);
        try {
            RegisteredTool registration = catalog.resolve(call.toolName());
            if (registration == null || def != registration.definition()) {
                throw new SecurityException(
                        "Tool definition does not match the registered tool: " + call.toolName());
            }
            ToolResult result =
                    switch (registration) {
                        case RegisteredTool.Plugin plugin -> executePlugin(call, plugin);
                        case RegisteredTool.Local local -> executeLocalCall(call, local);
                        case RegisteredTool.Remote remoteTool ->
                                executeRemote(
                                        call, remoteTool.definition(), remoteTool.capability());
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
                    ToolResultStatus.FAILURE,
                    ToolResultFormat.UNKNOWN,
                    "Tool execution failed: " + ToolErrors.normalize(e.getMessage()),
                    ToolErrorCode.GENERIC.TOOL_FAILURE);
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
                    ToolErrorCode.RESULT.TOOL_RESULT_TOO_LARGE);
        }
        String bounded =
                result.content().substring(0, MAX_TOOL_RESULT_CHARS)
                        + "\n[tool output truncated at "
                        + MAX_TOOL_RESULT_CHARS
                        + " chars]";
        return result.withContent(bounded);
    }

    // ── Implementation-detail API (not on the shared interface) ──────────────

    // ── Flavour dispatch ───────────────────────────────────────────────────────

    private @NonNull ToolResult executePlugin(
            @NonNull ToolCall call, RegisteredTool.@NonNull Plugin registration) {
        RemoteToolDefinition definition = registration.definition();
        requirePermit(call, definition);
        var selection = sessionPlugins;
        if (selection != null) {
            var context = ToolCallContextHolder.get();
            var session = context == null ? null : context.sessionId();
            if (session == null
                    || !selection.includes(
                            session.toString(), registration.runtime().identity().id()))
                throw new SecurityException("Plugin is not selected for this session");
        }
        try {
            var descriptor = registration.descriptor();
            JsonNode arguments = mapper.valueToTree(call.args());
            PluginSchema.validate(definition.inputSchema(), arguments);
            @NonNull Cancellation cancellation =
                    () ->
                            Thread.currentThread().isInterrupted()
                                    || registration.runtime().state() != PluginState.ACTIVE;
            JsonValue value =
                    registration
                            .runtime()
                            .execute(
                                    () ->
                                            descriptor.invoke(
                                                    PluginJson.object(arguments), cancellation));
            JsonNode result = PluginJson.toNode(value);
            PluginSchema.validate(PluginJson.toNode(descriptor.outputSchema()), result);
            return new ToolResult(
                    call.toolName(),
                    call.callId(),
                    ToolResultStatus.SUCCESS,
                    ToolResultFormat.JSON,
                    mapper.writeValueAsString(result),
                    null);
        } catch (Exception e) {
            // Worker messages and argument values must not enter logs or model-visible errors.
            throw new ToolExecutionException(
                    ToolResultStatus.FAILURE,
                    ToolResultFormat.PLAINTEXT,
                    ToolErrorCode.GENERIC.PLUGIN_CALL_FAILED,
                    "Plugin call failed or its contract was not satisfied.");
        }
    }

    private @NonNull ToolResult executeLocalCall(
            @NonNull ToolCall call, RegisteredTool.@NonNull Local registration) throws Exception {
        LocalToolDefinition definition = registration.definition();
        var runtime = registration.runtime();
        if (runtime != null) {
            if (runtime.state() != PluginState.ACTIVE)
                throw new SecurityException("Plugin is not active for this session");
            var selection = sessionPlugins;
            if (selection != null) {
                var context = ToolCallContextHolder.get();
                var session = context == null ? null : context.sessionId();
                if (session == null
                        || !selection.includes(session.toString(), runtime.identity().id()))
                    throw new SecurityException("Plugin is not selected for this session");
            }
        }
        JsonNode jsonArgs = mapper.valueToTree(call.args());
        NativeToolArgumentValidator.validate(definition.name(), jsonArgs, definition.argsClass());
        requirePermit(call, definition);
        if (runtime == null)
            return successfulResult(
                    call, definition, executeLocal(registration.handler(), jsonArgs));
        LocalOutcome outcome =
                runtime.execute(
                        () -> {
                            try {
                                return new LocalOutcome(
                                        executeLocal(registration.handler(), jsonArgs), null);
                            } catch (Exception failure) {
                                return new LocalOutcome(null, failure);
                            }
                        });
        Exception failure = outcome.failure();
        if (failure != null) throw failure;
        return successfulResult(call, definition, Nullness.requireNonNull(outcome.content()));
    }

    private record LocalOutcome(String content, Exception failure) {}

    private <T> @NonNull String executeLocal(
            @NonNull CapabilityTool<T> tool, @NonNull JsonNode jsonArgs) throws Exception {
        T args = mapper.treeToValue(jsonArgs, tool.getArgsClass());
        return tool.execute(Nullness.requireNonNull(args, "Tool arguments deserialized to null"));
    }

    /** External tool execution over the transport recorded during MCP discovery. */
    private @NonNull ToolResult executeRemote(
            @NonNull ToolCall call,
            @NonNull RemoteToolDefinition def,
            @NonNull RemoteCallCapability capability)
            throws IOException {
        requirePermit(call, def);
        JsonNode result = capability.call(call);
        boolean success = !result.path("isError").asBoolean(false);
        String content = remoteContent(result);
        return new ToolResult(
                call.toolName(),
                call.callId(),
                success ? ToolResultStatus.SUCCESS : ToolResultStatus.FAILURE,
                ToolResultFormat.UNKNOWN,
                content,
                success ? null : ToolErrorCode.NETWORK.REMOTE_TOOL_FAILED);
    }

    private static @NonNull ToolCallContext requirePermit(
            @NonNull ToolCall call, @NonNull ToolDefinition definition) {
        ToolCallContext context = ToolCallContextHolder.get();
        if (context == null) {
            throw new SecurityException(
                    "This tool call is not authorized for the current session; submit a fresh"
                            + " call.");
        }
        if (!context.executionPermit().authorizes(call, definition, context)) {
            throw new SecurityException(
                    "This tool call is not authorized for the current session; submit a fresh"
                            + " call.");
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
                        ToolErrorCode.RESULT.INVALID_JSON,
                        "Invalid JSON result: tool '"
                                + definition.name()
                                + "' declared json but returned no JSON value.");
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ToolExecutionException toolFailure) {
                throw toolFailure;
            }
            ToolErrors.failure(
                    ToolErrorCode.RESULT.INVALID_JSON,
                    "Invalid JSON result: tool '"
                            + definition.name()
                            + "' declared json but returned invalid JSON.");
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
