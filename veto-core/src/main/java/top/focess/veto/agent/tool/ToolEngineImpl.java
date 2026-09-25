package top.focess.veto.agent.tool;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.capability.CapabilityResolver;
import top.focess.veto.agent.capability.ImportedCredentialLeases;
import top.focess.veto.agent.capability.ProtectedWorkspaceReadCapabilityImpl;
import top.focess.veto.agent.capability.RemoteCallCapability;
import top.focess.veto.agent.capability.RemoteCallCapabilityImpl;
import top.focess.veto.agent.mcp.transport.McpJsonRpcClient;
import top.focess.veto.agent.mcp.transport.McpTransport;
import top.focess.veto.api.agent.capability.Capability;
import top.focess.veto.api.agent.capability.WorkspaceReadCapability;
import top.focess.veto.api.agent.capability.WorkspaceWriteCapability;
import top.focess.veto.api.agent.tool.AgentTool;
import top.focess.veto.api.agent.tool.CapabilityTool;
import top.focess.veto.api.agent.tool.ControlTool;
import top.focess.veto.api.agent.tool.HostCapabilityTool;
import top.focess.veto.api.agent.tool.NativeTool;
import top.focess.veto.api.agent.tool.PreparedTool;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolExecutionException;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolResultStatus;
import top.focess.veto.api.agent.tool.WorkspaceReadTool;
import top.focess.veto.api.agent.tool.WorkspaceWriteTool;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.Cancellation;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contract.Tool;
import top.focess.veto.integration.plugins.IsolatedExecutions;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.integration.plugins.SessionPlugins;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.plugin.runtime.ManagedPlugin;
import top.focess.veto.plugin.runtime.ManagedPluginWork;
import top.focess.veto.plugin.runtime.PluginJson;
import top.focess.veto.plugin.runtime.PluginSchema;
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
    private volatile SessionPlugins sessionPlugins;

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
                                    plugin,
                                    entry.id().localId()));
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
    @SuppressWarnings(
            "resource") // WHY: ManagedPlugin handle is owned by the plugin catalog, closed
    // elsewhere
    public PreparedInvocation prepare(
            @NonNull ToolCall call,
            @NonNull ToolDefinition definition,
            PluginHost.@NonNull Invocation invocation) {
        var registered = catalog.resolve(call.toolName());
        if (registered == null || registered.definition() != definition)
            throw new SecurityException("Preparation definition mismatch");
        if (!(registered instanceof RegisteredTool.Local local)
                || !(local.handler() instanceof PreparedTool<?> prepared)) return null;
        var runtime = local.runtime();
        var selected = sessionPlugins;
        if (runtime == null
                || runtime.state() != PluginState.ACTIVE
                || selected == null
                || !selected.includes(invocation.sessionId(), runtime.identity().id()))
            throw new SecurityException("Preparation requires the selected active contribution");
        JsonNode json = mapper.valueToTree(call.args());
        NativeToolArgumentValidator.validate(
                definition.name(), json, local.definition().argsClass());
        try {
            return runtime.execute(
                    () ->
                            ToolCallContextHolder.withoutEffects(
                                    () -> {
                                        return prepareTyped(
                                                prepared,
                                                json,
                                                runtime,
                                                invocation,
                                                call,
                                                definition.capability());
                                    }));
        } catch (Exception failure) {
            throw new SecurityException("Tool preparation failed", failure);
        }
    }

    private <T> @NonNull PreparedInvocation prepareTyped(
            @NonNull PreparedTool<T> tool,
            @NonNull JsonNode json,
            @NonNull ManagedPlugin runtime,
            PluginHost.@NonNull Invocation invocation,
            @NonNull ToolCall call,
            @NonNull ToolCapability capability) {
        try {
            return new PreparedInvocation(
                    runtime,
                    invocation,
                    call,
                    tool.prepare(
                            Nullness.requireNonNull(mapper.treeToValue(json, tool.getArgsClass())),
                            invocation),
                    capability);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid preparation arguments", failure);
        }
    }

    @Override
    @SuppressWarnings(
            "ThrowFromFinallyBlock") // WHY: a failed invocation finish must surface; receipts are
    // discarded first
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
            ExecutionReceipts.discard(ToolCallContextHolder.get());
            return new ToolResult(
                    call.toolName(), callId, e.status(), e.format(), e.content(), e.errorCode());
        } catch (Exception e) {
            ExecutionReceipts.discard(ToolCallContextHolder.get());
            log.warn("Tool '{}' execution failed.", call.toolName(), e);
            return new ToolResult(
                    call.toolName(),
                    callId,
                    ToolResultStatus.FAILURE,
                    ToolResultFormat.UNKNOWN,
                    "Tool execution failed: " + ToolErrors.normalize(e.getMessage()),
                    ToolErrorCode.GENERIC.TOOL_FAILURE);
        } finally {
            try {
                IsolatedExecutions.finishInvocation(ToolCallContextHolder.get());
            } catch (RuntimeException failure) {
                ExecutionReceipts.discard(ToolCallContextHolder.get());
                throw failure;
            } finally {
                ImportedCredentialLeases.releaseInvocation(ToolCallContextHolder.get());
                ToolCallContextHolder.setCurrentCallId("");
            }
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

    @SuppressWarnings(
            "resource") // WHY: ManagedPlugin handle is owned by the plugin catalog, closed
    // elsewhere
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
            Cancellation cancellation =
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

    @SuppressWarnings(
            "resource") // WHY: ManagedPlugin handle is owned by the plugin catalog, closed
    // elsewhere
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
        var invocation = requirePermit(call, definition);
        ToolPresentations.requireAvailable(
                definition, invocation.executionPermit().workspaceRoots());
        if (runtime == null)
            return successfulResult(
                    call, definition, executeLocal(registration.handler(), jsonArgs, false));
        LocalOutcome outcome =
                runtime.execute(
                        () -> {
                            try {
                                String content =
                                        executeLocal(registration.handler(), jsonArgs, true);
                                ToolCallContextHolder.guardWork(
                                        execution -> new ManagedPluginWork(runtime, execution));
                                ToolCallContextHolder.guardAwait(runtime::ownAwait);
                                return new LocalOutcome(content, null);
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
            @NonNull CapabilityTool<T> tool, @NonNull JsonNode jsonArgs, boolean injectHost)
            throws Exception {
        if (injectHost && tool instanceof HostCapabilityTool<?, ?> hosted)
            return executeHosted(hosted, jsonArgs);
        if (tool instanceof ControlTool<?> loop) return executeControl(loop, jsonArgs);
        if (tool instanceof WorkspaceReadTool<?> read) return executeWorkspaceRead(read, jsonArgs);
        if (tool instanceof WorkspaceWriteTool<?> write)
            return executeWorkspaceWrite(write, jsonArgs);
        T args = mapper.treeToValue(jsonArgs, tool.getArgsClass());
        return tool.execute(Nullness.requireNonNull(args, "Tool arguments deserialized to null"));
    }

    private <T, C extends @NonNull Capability> @NonNull String executeHosted(
            @NonNull HostCapabilityTool<T, C> tool, @NonNull JsonNode args) throws Exception {
        var context = applicationContext;
        if (context == null) throw new SecurityException("Host capability unavailable");
        return tool.execute(
                Nullness.requireNonNull(mapper.treeToValue(args, tool.getArgsClass())),
                Nullness.requireNonNull(context.getBean(tool.capabilityType())));
    }

    private <T> @NonNull String executeControl(
            @NonNull ControlTool<T> tool, @NonNull JsonNode jsonArgs) throws Exception {
        return tool.execute(
                Nullness.requireNonNull(mapper.treeToValue(jsonArgs, tool.getArgsClass())),
                ToolCallContextHolder.control());
    }

    private <T> @NonNull String executeWorkspaceRead(
            @NonNull WorkspaceReadTool<T> tool, @NonNull JsonNode jsonArgs) throws Exception {
        WorkspaceReadCapability capability =
                new ProtectedWorkspaceReadCapabilityImpl(sessionPlugins);
        return tool.execute(
                Nullness.requireNonNull(mapper.treeToValue(jsonArgs, tool.getArgsClass())),
                capability);
    }

    private <T> @NonNull String executeWorkspaceWrite(
            @NonNull WorkspaceWriteTool<T> tool, @NonNull JsonNode jsonArgs) throws Exception {
        return tool.execute(
                Nullness.requireNonNull(mapper.treeToValue(jsonArgs, tool.getArgsClass())),
                CapabilityResolver.require(ToolDocs.nonNullClass(WorkspaceWriteCapability.class)));
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
