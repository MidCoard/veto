package top.focess.veto.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.mcp.tools.RunCommandTool;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.sandbox.ChainMode;
import top.focess.veto.sandbox.Command;
import top.focess.veto.sandbox.CommandResult;
import top.focess.veto.sandbox.SandboxManager;
import top.focess.veto.sandbox.SandboxSubstrate;

/**
 * The tool engine implementation — manages server registrations, schema discovery, and tool
 * dispatching. The loop calls {@link ToolEngine}'s three loop-facing methods.
 *
 * <p>Dispatch by definition flavour:
 *
 * <ul>
 *   <li><b>Native</b> — in-process {@link NativeTool#execute}, EXCEPT {@code run_command} which
 *       routes through the session's {@link SandboxSubstrate}.
 *   <li><b>Agent</b> — bean dispatch via {@link AgentTool#executeFromJson}. Each agent tool is a
 *       self-contained {@link AgentTool} bean — just like native tools are self-contained {@link
 *       NativeTool} beans.
 *   <li><b>External</b> — forwarded over the registered {@link McpTransport}.
 * </ul>
 *
 * <p>{@code registerServer} + {@code McpTransport} + {@code executeTool} are implementation
 * details, intentionally absent from the shared {@link ToolEngine} interface. Remote tool
 * <i>discovery</i> (JSON-RPC {@code tools/list} over a transport) is beyond the schema
 * representation and is not implemented; remote tools are registered explicitly via {@link
 * #registerRemoteTool}.
 */
@Service
public class ToolEngineImpl implements ToolEngine {

    private static final Logger log = LoggerFactory.getLogger(ToolEngineImpl.class);

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

    @PostConstruct
    void init() {
        // Register native tools
        for (NativeTool<?> bean : nativeToolBeans) {
            NativeToolDefinition def = ToolSchemaCompiler.compileNative(bean);
            nativeDefs.put(def.name(), def);
            nativeByName.put(def.name(), bean);
            log.info("ToolEngine: registered native tool '{}'.", def.name());
        }

        // Discover and register agent tools via Spring
        for (AgentTool<?> bean : applicationContext.getBeansOfType(AgentTool.class).values()) {
            String toolName = bean.getName();
            agentBeans.put(toolName, bean);
            AgentToolDefinition def = AgentToolDefinition.from(toolName, bean.getArgsClass());
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
            for (RemoteToolDefinition t : tools) {
                remoteDefs.put(t.name(), t);
                transports.put(t.name(), transport);
            }
            log.info("ToolEngine: discovered {} tool(s) from {}.", tools.size(), transport);
            return tools;
        } catch (IOException e) {
            log.warn(
                    "ToolEngine: tools/list discovery failed for {}: {}",
                    transport,
                    e.getMessage());
            return java.util.List.of();
        }
    }

    @Override
    public @NonNull List<ToolDefinition> getActiveTools(@Nullable Set<String> whitelist) {
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
    public @Nullable ToolDefinition resolveDefinition(@NonNull String toolName) {
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
            return switch (def) {
                case NativeToolDefinition nativeDef -> executeNative(call, nativeDef);
                case AgentToolDefinition agentDef -> executeAgent(call, agentDef);
                case RemoteToolDefinition remoteDef -> executeRemote(call, remoteDef);
            };
        } catch (Exception e) {
            log.warn("Tool '{}' execution failed.", call.toolName(), e);
            return new ToolResult(
                    call.toolName(), callId, false, "Tool execution failed: " + e.getMessage());
        }
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
            @NonNull RiskCategory risk,
            @NonNull JsonNode inputSchema) {
        String prefixed = serverName + "__" + originalName;
        RemoteToolDefinition def =
                new RemoteToolDefinition(prefixed, description, risk, serverName, inputSchema);
        remoteDefs.put(prefixed, def);
        log.info("ToolEngine: registered remote tool '{}'.", prefixed);
        return def;
    }

    /** Low-level dispatch by tool name + raw arguments. */
    public @NonNull ToolResult executeTool(
            @NonNull String toolName, @NonNull Map<String, Object> arguments) {
        ToolDefinition def = resolveDefinition(toolName);
        if (def == null) {
            return new ToolResult(toolName, null, false, "Unknown tool: " + toolName);
        }
        return execute(new ToolCall(toolName, arguments, null), def);
    }

    // ── Flavour dispatch ───────────────────────────────────────────────────────

    private ToolResult executeNative(ToolCall call, NativeToolDefinition def) throws Exception {
        if ("run_command".equals(def.name())) {
            return executeRunCommand(call);
        }
        NativeTool<?> bean = nativeByName.get(def.name());
        if (bean == null) {
            return new ToolResult(
                    call.toolName(),
                    call.callId(),
                    false,
                    "No bean for native tool: " + def.name());
        }
        JsonNode jsonArgs = mapper.valueToTree(call.args());
        String result = bean.executeFromJson(jsonArgs, mapper);
        // Native tools signal "this call did not do what you asked" by returning a JSON envelope
        // `{"status":"error", "error": "<message>"}` rather than throwing. The engine has not
        // thrown, so a naive mapping would surface success=true and the IngressDefense observation
        // envelope would render [ok, ...] - misleading the agent into thinking the path was OK
        // while the body reports the path is missing. Sniff the canonical error prefix and
        // promote it to success=false so the envelope renders [error, ...] and downstream
        // masking/alerting sees the failure.
        boolean success = !isErrorJson(result);
        return new ToolResult(call.toolName(), call.callId(), success, result);
    }

    /**
     * Returns {@code true} when {@code body} starts with the canonical native-tool error envelope
     * {@code {"status":"error", ...}}. Centralised so the success-mapping rule lives in one place
     * and tools that follow the documented convention get the correct {@code success} flag for
     * free.
     */
    private static boolean isErrorJson(@Nullable String body) {
        if (body == null) return false;
        // Trim leading whitespace - tools may indent their JSON before returning.
        int i = 0;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        return body.startsWith("{\"status\":\"error\"", i);
    }

    /**
     * {@code run_command} — routes through the Sandbox substrate (no shell, argv[] direct exec).
     */
    private ToolResult executeRunCommand(ToolCall call) {
        RunCommandTool.Args args = mapper.convertValue(call.args(), RunCommandTool.Args.class);
        List<Command> commands =
                args.commands().stream().map(c -> new Command(c.executable(), c.args())).toList();
        ChainMode connect = parseChainMode(args.connect());
        Path cwd = Path.of(args.cwd());
        // Provision a sandbox rooted at the requested cwd. The per-session handle
        // (keyed by agentId) is a SandboxManager provision concern.
        var handle = sandboxManager.provision("runcmd-" + call.callId(), cwd);
        CommandResult result =
                sandboxManager
                        .substrate()
                        .runCommands(
                                handle, commands, Path.of("."), connect, Duration.ofMinutes(10));
        String content =
                (result.stdout().isEmpty() ? "" : result.stdout())
                        + (result.stderr().isEmpty() ? "" : "\n[stderr]\n" + result.stderr());
        return new ToolResult(call.toolName(), call.callId(), result.success(), content);
    }

    private ToolResult executeAgent(ToolCall call, AgentToolDefinition def) {
        AgentTool<?> bean = agentBeans.get(def.name());
        if (bean == null) {
            return new ToolResult(
                    call.toolName(), call.callId(), false, "Unknown agent tool: " + def.name());
        }
        try {
            JsonNode jsonArgs = mapper.valueToTree(call.args());
            String result = bean.executeFromJson(jsonArgs, mapper);
            return new ToolResult(call.toolName(), call.callId(), true, result);
        } catch (Exception e) {
            return new ToolResult(
                    call.toolName(), call.callId(), false, "Agent tool error: " + e.getMessage());
        }
    }

    /**
     * External tool execution over the registered {@link McpTransport}. The defines the transport
     * types but the JSON-RPC {@code tools/call} I/O over stdio/SSE/socket is beyond the schema
     * representation — not implemented.
     */
    private ToolResult executeRemote(ToolCall call, RemoteToolDefinition def) {
        McpTransport transport = transports.get(def.serverName());
        if (transport == null) {
            return new ToolResult(
                    call.toolName(),
                    call.callId(),
                    false,
                    "No transport registered for server: " + def.serverName());
        }
        return new ToolResult(
                call.toolName(),
                call.callId(),
                false,
                "Remote tool execution over "
                        + transport.getClass().getSimpleName()
                        + " is not implemented.");
    }

    private static ChainMode parseChainMode(String connect) {
        if (connect == null) return ChainMode.STOP_ON_FAILURE;
        return switch (connect) {
            case "RUN_ALL" -> ChainMode.RUN_ALL;
            case "PIPE" -> ChainMode.PIPE;
            default -> ChainMode.STOP_ON_FAILURE;
        };
    }
}
