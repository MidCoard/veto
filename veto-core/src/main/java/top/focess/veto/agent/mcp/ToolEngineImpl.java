package top.focess.veto.agent.mcp;

import static top.focess.veto.util.LogValues.safe;

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
@SuppressWarnings("DuplicatedCode") // Native and remote dispatch keep the same result envelope.
public class ToolEngineImpl implements ToolEngine {

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
            return switch (def) {
                case NativeToolDefinition nativeDef -> executeNative(call, nativeDef);
                case AgentToolDefinition agentDef -> executeAgent(call, agentDef);
                case RemoteToolDefinition remoteDef -> executeRemote(call, remoteDef);
            };
        } catch (Exception e) {
            log.warn("Tool '{}' execution failed.", call.toolName(), e);
            String detail = e.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = "Unexpected error with no diagnostic message";
            }
            return new ToolResult(
                    call.toolName(),
                    callId,
                    false,
                    errorEnvelope("Tool execution failed: " + detail));
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
            return new ToolResult(
                    toolName, null, false, errorEnvelope("Unknown tool: " + toolName));
        }
        return execute(new ToolCall(toolName, arguments, null), def);
    }

    // ── Flavour dispatch ───────────────────────────────────────────────────────

    private @NonNull ToolResult executeNative(
            @NonNull ToolCall call, @NonNull NativeToolDefinition def) throws Exception {
        JsonNode jsonArgs = mapper.valueToTree(call.args());
        try {
            NativeToolArgumentValidator.validate(def.name(), jsonArgs, def.argsClass());
        } catch (NativeToolArgumentValidator.InvalidArgumentsException e) {
            return new ToolResult(
                    call.toolName(), call.callId(), false, errorEnvelope(e.getMessage()));
        }
        if ("run_command".equals(def.name())) {
            return executeRunCommand(call);
        }
        NativeTool<?> bean = nativeByName.get(def.name());
        if (bean == null) {
            return new ToolResult(
                    call.toolName(),
                    call.callId(),
                    false,
                    errorEnvelope("No bean for native tool: " + def.name()));
        }
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
    private static boolean isErrorJson(String body) {
        if (body == null) return false;
        // Trim leading whitespace - tools may indent their JSON before returning.
        int i = 0;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        return body.startsWith("{\"status\":\"error\"", i);
    }

    /**
     * The uniform error envelope for engine-level failures (unknown tool, missing bean, handler
     * exception, remote transport down). Tool-detected errors already speak this envelope; the
     * engine synthesizes the same shape here so every tool error the model can ever see matches one
     * prefix - it learns a single branch: the call did not happen, read {@code error}, replan.
     */
    private static @NonNull String errorEnvelope(String message) {
        String detail =
                message == null || message.isBlank()
                        ? "Unexpected error with no diagnostic message"
                        : message;
        return "{\"status\":\"error\",\"error\":\"" + jsonEscape(detail) + "\"}";
    }

    /**
     * Minimal JSON string-body escaping for {@link #errorEnvelope} (quotes, backslashes, controls).
     */
    private static @NonNull String jsonEscape(@NonNull String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * {@code run_command} — routes through the Sandbox substrate (no shell, argv[] direct exec).
     */
    private @NonNull ToolResult executeRunCommand(@NonNull ToolCall call) {
        RunCommandTool.Args args =
                mapper.convertValue(call.args(), ToolDocs.nonNullClass(RunCommandTool.Args.class));
        if (args == null) {
            return new ToolResult(
                    call.toolName(),
                    call.callId(),
                    false,
                    errorEnvelope("run_command arguments must be a JSON object"));
        }
        int timeout = args.timeout();
        Duration timeoutDur = timeout <= 0 ? Duration.ZERO : Duration.ofSeconds(timeout);
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
                        .runCommands(handle, commands, Path.of("."), connect, timeoutDur);
        String content = commandOutput(result);
        return new ToolResult(call.toolName(), call.callId(), result.success(), content);
    }

    private static @NonNull String commandOutput(@NonNull CommandResult result) {
        String stderr = result.stderr();
        String content =
                (result.stdout().isEmpty() ? "" : result.stdout())
                        + (stderr == null || stderr.isEmpty() ? "" : "\n[stderr]\n" + stderr);
        // A non-zero exit is not an error envelope - the command ran and its output is the truth
        // the model asked for. The exit code rides as a trailing CONTENT line so the model can
        // branch on it without a separate status channel.
        return result.exitCode() == 0
                ? content
                : content + "\n(exit code: " + result.exitCode() + ")";
    }

    private @NonNull ToolResult executeAgent(
            @NonNull ToolCall call, @NonNull AgentToolDefinition def) {
        AgentTool<?> bean = agentBeans.get(def.name());
        if (bean == null) {
            return new ToolResult(
                    call.toolName(),
                    call.callId(),
                    false,
                    errorEnvelope("Unknown agent tool: " + def.name()));
        }
        try {
            JsonNode jsonArgs = mapper.valueToTree(call.args());
            NativeToolArgumentValidator.validate(def.name(), jsonArgs, def.argsClass());
            String result = bean.executeFromJson(jsonArgs, mapper);
            return new ToolResult(call.toolName(), call.callId(), true, result);
        } catch (Exception e) {
            return new ToolResult(
                    call.toolName(),
                    call.callId(),
                    false,
                    errorEnvelope("Agent tool error: " + e.getMessage()));
        }
    }

    /** External tool execution over the transport recorded during MCP discovery. */
    private @NonNull ToolResult executeRemote(
            @NonNull ToolCall call, @NonNull RemoteToolDefinition def) throws IOException {
        McpTransport transport = transports.get(def.serverName());
        if (transport == null) {
            return new ToolResult(
                    call.toolName(),
                    call.callId(),
                    false,
                    errorEnvelope("No transport registered for server: " + def.serverName()));
        }
        JsonNode result = new McpJsonRpcClient(mapper).callTool(transport, def.name(), call.args());
        boolean success = !result.path("isError").asBoolean(false);
        String content = remoteContent(result);
        return new ToolResult(call.toolName(), call.callId(), success, content);
    }

    private static @NonNull String remoteContent(@NonNull JsonNode result) {
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
        return text.isEmpty() ? result.toString() : text.toString();
    }

    private static @NonNull ChainMode parseChainMode(String connect) {
        if (connect == null) return ChainMode.STOP_ON_FAILURE;
        return switch (connect) {
            case "RUN_ALL" -> ChainMode.RUN_ALL;
            case "PIPE" -> ChainMode.PIPE;
            default -> ChainMode.STOP_ON_FAILURE;
        };
    }
}
