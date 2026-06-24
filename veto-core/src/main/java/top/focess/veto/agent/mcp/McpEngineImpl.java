package top.focess.veto.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.mcp.tools.CreateGroupArgs;
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

/**
 * The MCP engine implementation — manages server registrations, schema discovery, and tool
 * dispatching. Part 5 owns this; Part 1's loop calls {@link McpEngine}'s three loop-facing methods.
 * Transcribed (implementation) from {@code plans/mvp-core/part5_agent/mcp_tool_foundation.md} §2.
 *
 * <p>Dispatch by definition flavour (§4.2 / §5.1):
 *
 * <ul>
 *   <li><b>Native</b> — in-process {@link NativeMcpTool#execute}, EXCEPT {@code run_command} which
 *       routes through the session's {@link top.focess.veto.sandbox.SandboxSubstrate} (§10.6).
 *   <li><b>Agent</b> — engine handler ({@code load_skill} → {@link SkillRegistry} hash-verify +
 *       body; {@code create_group} → Phase-2 stub).
 *   <li><b>External</b> — forwarded over the registered {@link McpTransport}.
 * </ul>
 *
 * <p>{@code registerServer} + {@code McpTransport} + {@code executeTool} are Part-5-owned
 * implementation details, intentionally absent from the shared {@link McpEngine} interface. Remote
 * tool <i>discovery</i> (JSON-RPC {@code tools/list} over a transport) is beyond the LLD §2.1
 * schema representation and is not implemented in the MVP; remote tools are registered explicitly
 * via {@link #registerRemoteTool}.
 */
@Service
public class McpEngineImpl implements McpEngine {

    private static final Logger log = LoggerFactory.getLogger(McpEngineImpl.class);

    private final ObjectMapper mapper;
    private final List<NativeMcpTool<?>> nativeToolBeans;
    private final SandboxManager sandboxManager;
    private final SkillRegistry skillRegistry;

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
        agentDefs.put(
                "create_group",
                new AgentToolDefinition(
                        "create_group",
                        "Spawn a delegation (a group of agents) to accomplish a goal. Bounded by the "
                                + "resource gate at spawn time.",
                        CreateGroupArgs.class,
                        Map.of("description", ParamCategory.GENERIC)));
        log.info("McpEngine: initialized. {} native tool(s), 2 agent tool(s).", nativeDefs.size());
    }

    @Override
    public List<ToolDefinition> getActiveTools(Set<String> whitelist) {
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
    public ToolDefinition resolveDefinition(String toolName) {
        ToolDefinition def = nativeDefs.get(toolName);
        if (def != null) return def;
        def = agentDefs.get(toolName);
        if (def != null) return def;
        return remoteDefs.get(toolName);
    }

    @Override
    public McpToolResult execute(ToolCall call, ToolDefinition def) {
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
    public void registerServer(String serverName, McpTransport transport) {
        transports.put(serverName, transport);
        log.info("McpEngine: registered server transport '{}'.", serverName);
    }

    /**
     * Registers an external tool discovered from a registered MCP server. Names are prefixed {@code
     * {serverName}__{originalToolName}} (§8).
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
    public McpToolResult executeTool(String toolName, Map<String, Object> arguments) {
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
        // MVP: provision a sandbox rooted at the requested cwd. The per-session handle
        // (keyed by agentId) is Part 1's SandboxManager provision concern.
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
            case "create_group" ->
                    new McpToolResult(
                            call.toolName(),
                            call.callId(),
                            false,
                            "create_group is Phase-2 (out of MVP scope).");
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
     * External tool execution over the registered {@link McpTransport}. <b>MVP stub</b>: the LLD §3
     * defines the transport types but the JSON-RPC {@code tools/call} I/O over stdio/SSE/socket is
     * beyond the LLD §2.1 schema representation — not implemented in the MVP.
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
                        + " is not implemented in the MVP.");
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
