package top.focess.veto.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.mcp.tools.LoadSkillArgs;
import top.focess.veto.agent.mcp.tools.RunCommandTool;
import top.focess.veto.agent.skills.Skill;
import top.focess.veto.agent.skills.SkillRegistry;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.sandbox.ChainMode;
import top.focess.veto.sandbox.Command;
import top.focess.veto.sandbox.CommandResult;
import top.focess.veto.sandbox.SandboxManager;
import top.focess.veto.sandbox.SandboxSubstrate;

/**
 * The MCP engine implementation — manages server registrations, schema discovery, and tool
 * dispatching. The loop calls {@link McpEngine}'s three loop-facing methods.
 *
 * <p>Dispatch by definition flavour:
 *
 * <ul>
 *   <li><b>Native</b> — in-process {@link NativeMcpTool#execute}, EXCEPT {@code run_command} which
 *       routes through the session's {@link SandboxSubstrate}.
 *   <li><b>Agent</b> — engine handler ({@code load_skill} → {@link SkillRegistry} hash-verify +
 *       body). {@code create_group} and the other group tools are <b>native</b> tools ({@code
 *       GroupTools}, component-scanned) — not agent tools.
 *   <li><b>External</b> — forwarded over the registered {@link McpTransport}.
 * </ul>
 *
 * <p>{@code registerServer} + {@code McpTransport} + {@code executeTool} are implementation
 * details, intentionally absent from the shared {@link McpEngine} interface. Remote tool
 * <i>discovery</i> (JSON-RPC {@code tools/list} over a transport) is beyond the schema
 * representation and is not implemented; remote tools are registered explicitly via {@link
 * #registerRemoteTool}.
 */
@Service
public class McpEngineImpl implements McpEngine {

    private static final Logger log = LoggerFactory.getLogger(McpEngineImpl.class);

    private final @NonNull ObjectMapper mapper;
    private final @NonNull List<NativeMcpTool<?>> nativeToolBeans;
    private final @NonNull SandboxManager sandboxManager;
    private final @NonNull SkillRegistry skillRegistry;

    private final Map<String, NativeToolDefinition> nativeDefs = new ConcurrentHashMap<>();
    private final Map<String, NativeMcpTool<?>> nativeByName = new ConcurrentHashMap<>();
    private final Map<String, AgentToolDefinition> agentDefs = new ConcurrentHashMap<>();
    private final Map<String, RemoteToolDefinition> remoteDefs = new ConcurrentHashMap<>();
    private final Map<String, McpTransport> transports = new ConcurrentHashMap<>();

    public McpEngineImpl(
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) ObjectMapper mapper,
            List<NativeMcpTool<?>> nativeToolBeans,
            SandboxManager sandboxManager,
            SkillRegistry skillRegistry) {
        this.mapper = mapper;
        this.nativeToolBeans = nativeToolBeans;
        this.sandboxManager = sandboxManager;
        this.skillRegistry = skillRegistry;
    }

    @PostConstruct
    void init() {
        for (NativeMcpTool<?> bean : nativeToolBeans) {
            NativeToolDefinition def = ToolSchemaCompiler.compileNative(bean);
            nativeDefs.put(def.name(), def);
            nativeByName.put(def.name(), bean);
            log.info("McpEngine: registered native tool '{}'.", def.name());
        }
        agentDefs.put(
                "load_skill",
                new AgentToolDefinition(
                        "load_skill",
                        "Loads the detailed guidelines and instruction steps for a specific skill. "
                                + "Returns the full SKILL.md body in the observation.",
                        LoadSkillArgs.class,
                        Map.of("skillName", ParamCategory.GENERIC)));
        // create_group is a NATIVE tool (GroupTools.CreateGroup, @ToolSecurity) — not an agent
        // tool. The duplicate agent-tool entry here was dead code: resolveDefinition checks
        // nativeDefs first, so the native GroupTools.CreateGroup (stub) always won. Removed.
        log.info("McpEngine: initialized. {} native tool(s), 1 agent tool(s).", nativeDefs.size());
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
            log.info("McpEngine: discovered {} tool(s) from {}.", tools.size(), transport);
            return tools;
        } catch (IOException e) {
            log.warn(
                    "McpEngine: tools/list discovery failed for {}: {}", transport, e.getMessage());
            return java.util.List.of();
        }
    }

    @Override
    public @NonNull List<ToolDefinition> getActiveTools(@NonNull Set<String> whitelist) {
        List<ToolDefinition> active = new ArrayList<>();
        for (NativeToolDefinition def : nativeDefs.values()) {
            if (whitelist == null || whitelist.contains(def.name())) {
                active.add(def);
            }
        }
        active.addAll(agentDefs.values()); // agent tools are always-on (runtime-excluded from the
        // persona whitelist)
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
    public @NonNull McpToolResult execute(@NonNull ToolCall call, @NonNull ToolDefinition def) {
        String callId = call.callId();
        try {
            return switch (def) {
                case NativeToolDefinition nativeDef -> executeNative(call, nativeDef);
                case AgentToolDefinition agentDef -> executeAgent(call, agentDef);
                case RemoteToolDefinition remoteDef -> executeRemote(call, remoteDef);
            };
        } catch (Exception e) {
            log.warn("Tool '{}' execution failed.", call.toolName(), e);
            return new McpToolResult(
                    call.toolName(), callId, false, "Tool execution failed: " + e.getMessage());
        }
    }

    // ── Implementation-detail API (not on the shared interface) ──────────────

    /** Registers a new MCP server transport. Stores it keyed by {@code serverName}. */
    public void registerServer(@NonNull String serverName, @NonNull McpTransport transport) {
        transports.put(serverName, transport);
        log.info("McpEngine: registered server transport '{}'.", serverName);
    }

    /**
     * Registers an external tool discovered from a registered MCP server. Names are prefixed {@code
     * {serverName}__{originalToolName}}.
     */
    public RemoteToolDefinition registerRemoteTool(
            String serverName,
            String originalName,
            String description,
            RiskCategory risk,
            JsonNode inputSchema) {
        String prefixed = serverName + "__" + originalName;
        RemoteToolDefinition def =
                new RemoteToolDefinition(prefixed, description, risk, serverName, inputSchema);
        remoteDefs.put(prefixed, def);
        log.info("McpEngine: registered remote tool '{}'.", prefixed);
        return def;
    }

    /** Low-level dispatch by tool name + raw arguments. */
    public @NonNull McpToolResult executeTool(
            @NonNull String toolName, @NonNull Map<String, Object> arguments) {
        ToolDefinition def = resolveDefinition(toolName);
        if (def == null) {
            return new McpToolResult(toolName, null, false, "Unknown tool: " + toolName);
        }
        return execute(new ToolCall(toolName, arguments, null), def);
    }

    // ── Flavour dispatch ───────────────────────────────────────────────────────

    private McpToolResult executeNative(ToolCall call, NativeToolDefinition def) throws Exception {
        if ("run_command".equals(def.name())) {
            return executeRunCommand(call);
        }
        NativeMcpTool<?> bean = nativeByName.get(def.name());
        if (bean == null) {
            return new McpToolResult(
                    call.toolName(),
                    call.callId(),
                    false,
                    "No bean for native tool: " + def.name());
        }
        JsonNode jsonArgs = mapper.valueToTree(call.args());
        String result = bean.executeFromJson(jsonArgs, mapper);
        return new McpToolResult(call.toolName(), call.callId(), true, result);
    }

    /**
     * {@code run_command} — routes through the Sandbox substrate (no shell, argv[] direct exec).
     */
    private McpToolResult executeRunCommand(ToolCall call) {
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
        return new McpToolResult(call.toolName(), call.callId(), result.success(), content);
    }

    private McpToolResult executeAgent(ToolCall call, AgentToolDefinition def) {
        return switch (def.name()) {
            case "load_skill" -> executeLoadSkill(call);
            default ->
                    new McpToolResult(
                            call.toolName(),
                            call.callId(),
                            false,
                            "Unknown agent tool: " + def.name());
        };
    }

    private McpToolResult executeLoadSkill(ToolCall call) {
        LoadSkillArgs args = mapper.convertValue(call.args(), LoadSkillArgs.class);
        var skill = skillRegistry.loadVerified(args.skillName());
        if (skill.isEmpty()) {
            return new McpToolResult(
                    call.toolName(),
                    call.callId(),
                    false,
                    "Skill '" + args.skillName() + "' not found or tampered.");
        }
        Skill s = skill.get();
        return new McpToolResult(call.toolName(), call.callId(), true, s.promptInstructions());
    }

    /**
     * External tool execution over the registered {@link McpTransport}. The defines the transport
     * types but the JSON-RPC {@code tools/call} I/O over stdio/SSE/socket is beyond the schema
     * representation — not implemented.
     */
    private McpToolResult executeRemote(ToolCall call, RemoteToolDefinition def) {
        McpTransport transport = transports.get(def.serverName());
        if (transport == null) {
            return new McpToolResult(
                    call.toolName(),
                    call.callId(),
                    false,
                    "No transport registered for server: " + def.serverName());
        }
        return new McpToolResult(
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
