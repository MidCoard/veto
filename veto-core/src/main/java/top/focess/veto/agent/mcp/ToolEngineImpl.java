package top.focess.veto.agent.mcp;

import static top.focess.veto.util.LogValues.safe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
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
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.mcp.tools.RunCommandTool;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.sandbox.ChainMode;
import top.focess.veto.sandbox.Command;
import top.focess.veto.sandbox.CommandResult;
import top.focess.veto.sandbox.SandboxManager;
import top.focess.veto.sandbox.SandboxProfile;
import top.focess.veto.sandbox.SandboxSubstrate;

/**
 * The tool engine implementation — manages server registrations, schema discovery, and tool
 * dispatching. The loop calls {@link ToolEngine}'s three loop-facing methods.
 *
 * <p>Dispatch by definition flavour:
 *
 * <ul>
 *   <li><b>Native</b> — typed dispatch after capability validation. Workspace paths are replaced by
 *       Gateway-authorized canonical targets; process tools route through {@link SandboxSubstrate}
 *       or the background-task service.
 *   <li><b>Agent</b> — bean dispatch via {@link AgentTool#executeFromJson}. Each agent tool is a
 *       self-contained {@link AgentTool} bean — just like native tools are self-contained {@link
 *       NativeTool} beans.
 *   <li><b>External</b> — forwarded over the registered {@link McpTransport}.
 * </ul>
 *
 * <p>{@code registerServer} + {@code McpTransport} are implementation details, intentionally absent
 * from the shared {@link ToolEngine} interface. Remote tool <i>discovery</i> (JSON-RPC {@code
 * tools/list} over a transport) is beyond the schema representation and is implemented by {@link
 * #discoverAndRegister(McpTransport)}; callers may also register a definition explicitly via {@link
 * #registerRemoteTool}.
 */
@Service
@SuppressWarnings("DuplicatedCode") // Native and remote dispatch keep the same result envelope.
public class ToolEngineImpl implements ToolEngine, SmartInitializingSingleton {

    private static final int MAX_TOOL_RESULT_CHARS = 1_000_000;

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.mcp.ToolEngineImpl");

    private final @NonNull ObjectMapper mapper;
    private final @NonNull List<NativeTool<?>> nativeToolBeans;
    private final @NonNull SandboxManager sandboxManager;
    private final @NonNull ApplicationContext applicationContext;

    private final @NonNull Map<String, NativeToolDefinition> nativeDefs = new ConcurrentHashMap<>();
    private final @NonNull Map<String, NativeTool<?>> nativeByName = new ConcurrentHashMap<>();
    private final @NonNull Map<String, AgentToolDefinition> agentDefs = new ConcurrentHashMap<>();
    private final @NonNull Map<String, AgentTool<?>> agentBeans = new LinkedHashMap<>();
    private final @NonNull Map<String, RemoteToolDefinition> remoteDefs = new ConcurrentHashMap<>();
    private final @NonNull Map<String, McpTransport> transports = new ConcurrentHashMap<>();

    public ToolEngineImpl(
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NonNull ObjectMapper mapper,
            @NonNull List<NativeTool<?>> nativeToolBeans,
            @NonNull SandboxManager sandboxManager,
            @NonNull ApplicationContext applicationContext) {
        this.mapper = mapper;
        this.nativeToolBeans = nativeToolBeans;
        this.sandboxManager = sandboxManager;
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
            ToolContractValidator.validate(def);
            ensureUniqueName(def.name());
            nativeDefs.put(def.name(), def);
            nativeByName.put(def.name(), bean);
            log.info("ToolEngine: registered native tool '{}'.", def.name());
        }

        // Discover and register agent tools via Spring
        for (AgentTool<?> bean : applicationContext.getBeansOfType(AgentTool.class).values()) {
            String toolName = bean.getName();
            agentBeans.put(toolName, bean);
            AgentToolDefinition def =
                    AgentToolDefinition.from(toolName, bean.getArgsClass(), bean.getCapability());
            ToolContractValidator.validate(def);
            ensureUniqueName(def.name());
            agentDefs.put(def.name(), def);
            log.info("ToolEngine: registered agent tool '{}'.", def.name());
        }

        log.info(
                "ToolEngine: initialized. {} native tool(s), {} agent tool(s).",
                nativeDefs.size(),
                agentDefs.size());
    }

    /** Discover tools from a remote MCP server via JSON-RPC tools/list and register them. */
    public java.util.@NonNull List<RemoteToolDefinition> discoverAndRegister(
            @NonNull McpTransport transport) {
        try {
            java.util.List<RemoteToolDefinition> tools =
                    new McpJsonRpcClient(mapper).discoverTools(transport);
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
                remoteDefs.put(t.name(), t);
                transports.put(t.serverName(), transport);
            }
            log.info("ToolEngine: discovered {} tool(s) from {}.", tools.size(), transport);
            return tools;
        } catch (IOException e) {
            log.warn(
                    "ToolEngine: tools/list discovery failed for {}: {}",
                    transport,
                    safe(e.getMessage()));
            return java.util.List.of();
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
        try {
            ToolResult result =
                    switch (def) {
                        case NativeToolDefinition nativeDef -> executeNative(call, nativeDef);
                        case AgentToolDefinition agentDef -> executeAgent(call, agentDef);
                        case RemoteToolDefinition remoteDef -> executeRemote(call, remoteDef);
                    };
            return boundResult(result);
        } catch (ToolExecutionException e) {
            return new ToolResult(
                    call.toolName(), callId, false, ToolErrors.normalize(e.getMessage()));
        } catch (Exception e) {
            log.warn("Tool '{}' execution failed.", call.toolName(), e);
            return new ToolResult(
                    call.toolName(),
                    callId,
                    false,
                    "Tool execution failed: " + ToolErrors.normalize(e.getMessage()));
        }
    }

    private static @NonNull ToolResult boundResult(@NonNull ToolResult result) {
        if (result.content().length() <= MAX_TOOL_RESULT_CHARS) {
            return result;
        }
        String bounded =
                result.content().substring(0, MAX_TOOL_RESULT_CHARS)
                        + "\n[tool output truncated at "
                        + MAX_TOOL_RESULT_CHARS
                        + " chars]";
        return result.withContent(bounded);
    }

    // ── Implementation-detail API (not on the shared interface) ──────────────

    /** Registers a new MCP server transport. Stores it keyed by {@code serverName}. */
    public void registerServer(@NonNull String serverName, @NonNull McpTransport transport) {
        transports.put(serverName, transport);
        log.info("ToolEngine: registered server transport '{}'.", serverName);
    }

    /**
     * Registers an external tool discovered from a registered MCP server. Names are prefixed {@code
     * {serverName}__{originalToolName}}.
     */
    public @NonNull RemoteToolDefinition registerRemoteTool(
            @NonNull String serverName,
            @NonNull String originalName,
            @NonNull String description,
            @NonNull JsonNode inputSchema) {
        String prefixed = serverName + "__" + originalName;
        RemoteToolDefinition def =
                new RemoteToolDefinition(
                        prefixed, description, RiskCategory.NETWORK, serverName, inputSchema);
        ToolContractValidator.validate(def);
        ensureUniqueName(def.name());
        remoteDefs.put(prefixed, def);
        log.info("ToolEngine: registered remote tool '{}'.", prefixed);
        return def;
    }

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
        try {
            NativeToolArgumentValidator.validate(def.name(), jsonArgs, def.argsClass());
        } catch (NativeToolArgumentValidator.InvalidArgumentsException e) {
            return new ToolResult(
                    call.toolName(), call.callId(), false, ToolErrors.normalize(e.getMessage()));
        }
        jsonArgs = authorizedArguments(call, jsonArgs, def);
        if ("run_command".equals(def.name())) {
            return executeRunCommand(call, jsonArgs);
        }
        NativeTool<?> bean = nativeByName.get(def.name());
        if (bean == null) {
            return new ToolResult(
                    call.toolName(),
                    call.callId(),
                    false,
                    "No bean for native tool: " + def.name());
        }
        String result = bean.executeFromJson(jsonArgs, mapper);
        validateSuccessfulResult(def, result);
        return new ToolResult(
                call.toolName(),
                call.callId(),
                ToolResultStatus.SUCCESS,
                declaredFormat(def),
                result,
                null);
    }

    /**
     * Replaces screened filesystem strings with the canonical targets bound to the execution
     * permit. Filesystem/process tools fail closed when invoked outside AgentRunner's authorized
     * execution scope.
     */
    private @NonNull JsonNode authorizedArguments(
            @NonNull ToolCall call,
            @NonNull JsonNode jsonArgs,
            @NonNull NativeToolDefinition definition) {
        ToolCallContext context = ToolCallContextHolder.get();
        if (context == null) {
            throw new SecurityException(
                    "Tool requires an authorized execution context: " + definition.name());
        }
        ToolExecutionPermit permit = context.executionPermit();
        if (!permit.matchesCall(call)) {
            throw new SecurityException(
                    "Tool arguments do not match the screened execution permit: "
                            + definition.name());
        }
        boolean needsFilesystemPermit =
                definition.capability() == ToolCapability.WORKSPACE_READ
                        || definition.capability() == ToolCapability.WORKSPACE_WRITE
                        || definition.capability() == ToolCapability.PROCESS_EXECUTION;
        if (!needsFilesystemPermit) {
            return jsonArgs;
        }
        if (!(jsonArgs instanceof ObjectNode objectArgs)) {
            throw new SecurityException("Tool arguments must be an object: " + definition.name());
        }
        ObjectNode authorized = objectArgs.deepCopy();
        for (var entry : definition.paramHints().entrySet()) {
            if (entry.getValue() != ParamCategory.FILESYSTEM_PATH) {
                continue;
            }
            ToolExecutionPermit.AuthorizedPath path = permit.path(entry.getKey());
            Path hostPath = path == null ? null : path.hostPath();
            if (path == null || hostPath == null) {
                throw new SecurityException(
                        "Missing authorized filesystem target for parameter '"
                                + entry.getKey()
                                + "'");
            }
            String supplied = objectArgs.path(entry.getKey()).asText("");
            if (!path.requestedPath().equals(supplied)) {
                throw new SecurityException(
                        "Filesystem argument does not match its execution permit: "
                                + entry.getKey());
            }
            authorized.put(entry.getKey(), hostPath.toString());
        }
        return authorized;
    }

    /**
     * {@code run_command} — routes through the Sandbox substrate (no shell, argv[] direct exec).
     */
    private @NonNull ToolResult executeRunCommand(
            @NonNull ToolCall call, @NonNull JsonNode authorizedArguments) {
        RunCommandTool.Args args =
                mapper.convertValue(
                        authorizedArguments, ToolDocs.nonNullClass(RunCommandTool.Args.class));
        if (args == null) {
            return new ToolResult(
                    call.toolName(),
                    call.callId(),
                    false,
                    "run_command arguments must be a JSON object");
        }
        int timeout = args.timeout();
        if (timeout < 0) {
            return new ToolResult(
                    call.toolName(), call.callId(), false, "timeout must be zero or positive");
        }
        if (args.commands().isEmpty()) {
            return new ToolResult(
                    call.toolName(),
                    call.callId(),
                    false,
                    "commands must contain at least one command");
        }
        Duration timeoutDur = timeout == 0 ? Duration.ZERO : Duration.ofSeconds(timeout);
        List<Command> commands =
                args.commands().stream().map(c -> new Command(c.executable(), c.args())).toList();
        ChainMode requestedConnect = args.connect();
        ChainMode connect = requestedConnect != null ? requestedConnect : ChainMode.STOP_ON_FAILURE;
        Path cwd = Path.of(args.cwd());
        ToolCallContext context = ToolCallContextHolder.get();
        if (context == null || !context.executionPermit().matchesCall(call)) {
            throw new SecurityException("run_command requires its screened execution permit");
        }
        ToolExecutionPermit permit = context.executionPermit();
        Path workspaceRoot = permit.sandboxRoot("cwd");
        SandboxProfile profile =
                SandboxProfile.forExecution(
                        workspaceRoot,
                        permit.protectedPaths(),
                        Boolean.TRUE.equals(args.network()));
        String sandboxId = "runcmd-" + call.callId();
        var handle = sandboxManager.provision(sandboxId, profile);
        try {
            CommandResult result =
                    sandboxManager
                            .substrate()
                            .runCommands(handle, commands, cwd, connect, timeoutDur);
            String content = commandOutput(result);
            if (!Boolean.TRUE.equals(args.network()) && !result.success()) {
                content +=
                        "\n[sandbox network is disabled; if this command requires network access, "
                                + "submit a fresh call with `network: true` for Gateway approval]";
            }
            return new ToolResult(
                    call.toolName(),
                    call.callId(),
                    result.success() ? ToolResultStatus.SUCCESS : ToolResultStatus.FAILURE,
                    ToolResultFormat.PLAINTEXT,
                    content,
                    result.success() ? null : "COMMAND_FAILED");
        } finally {
            sandboxManager.deprovision(sandboxId);
        }
    }

    private static @NonNull String commandOutput(@NonNull CommandResult result) {
        String stderr = result.stderr();
        String content =
                (result.stdout().isEmpty() ? "" : result.stdout())
                        + (stderr.isEmpty() ? "" : "\n[stderr]\n" + stderr);
        // The exit code rides in the content as well as the result status so every provider gives
        // the model the exact process outcome.
        return result.exitCode() == 0
                ? content
                : content + "\n(exit code: " + result.exitCode() + ")";
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
            String result = bean.executeFromJson(jsonArgs, mapper);
            validateSuccessfulResult(def, result);
            return new ToolResult(
                    call.toolName(),
                    call.callId(),
                    ToolResultStatus.SUCCESS,
                    declaredFormat(def),
                    result,
                    null);
        } catch (ToolExecutionException e) {
            return new ToolResult(
                    call.toolName(), call.callId(), false, ToolErrors.normalize(e.getMessage()));
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
        ToolCallContext context = ToolCallContextHolder.get();
        if (context == null || !context.executionPermit().matchesCall(call)) {
            throw new SecurityException(
                    "Remote tool requires its screened execution permit: " + def.name());
        }
        McpTransport transport = transports.get(def.serverName());
        if (transport == null) {
            return new ToolResult(
                    call.toolName(),
                    call.callId(),
                    false,
                    "No transport registered for server: " + def.serverName());
        }
        JsonNode result = new McpJsonRpcClient(mapper).callTool(transport, def.name(), call.args());
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
            JsonNode parsed = mapper.readTree(result);
            if (parsed == null) {
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
}
