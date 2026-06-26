package top.focess.veto.agent.intercept;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.screening.Screening;
import top.focess.veto.agent.screening.ScreeningMode;
import top.focess.veto.agent.screening.ScreeningOutcome;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolCall;

/**
 * The single human-in-the-loop registry. Owns four responsibilities (screening_model.md §7,
 * network_hitl_protocol.md §3):
 *
 * <ol>
 *   <li>{@link #decide} — resolve the {@link GatewayResult} (via the {@link ScreeningMode} matrix)
 *       + permission grants into an {@link ApprovalDecision}. NotScreened → AutoApprove; {@link
 *       ScreeningOutcome#APPROVE} → AutoApprove; {@link ScreeningOutcome#REFUSED} → {@link
 *       ApprovalDecision.Refused} (CRITICAL only, grant-immune); {@link ScreeningOutcome#ASK} →
 *       AutoApprove on a matching grant, else a {@link ApprovalDecision.Prompt} carrying the
 *       tool-declared option set.
 *   <li>{@link #register} / {@link #await} — park the agent's virtual thread on a {@link
 *       CompletableFuture} keyed by {@code (agentId, callId)} (the Loom yield).
 *   <li>{@link #resolve} — complete the future with the user's {@link InterceptResolution}; on
 *       {@code _LIKE_THIS} options, cache a {@link PermissionGrant} (read / write / command) with
 *       the appropriate match key.
 *   <li>Tool-declared option sets — {@link #scenarioFor} maps tool family + danger to a {@link
 *       VetoScenario}; {@link #optionsFor} returns that scenario's option list (per
 *       screening_model.md §8).
 * </ol>
 *
 * <p>This is <b>not</b> the {@code LoopInterceptor} plugin chain — HITL is a dedicated mechanism,
 * not signaled through plugins. {@code agentId} is passed explicitly so grants are scoped per-agent
 * without a thread-local indirection.
 */
@Component
public class HitlRegistry {

    /**
     * The runtime-tunable screening matrix ({@link ScreeningMode#cell}). Defaults to {@link
     * ScreeningMode#STRICT}; {@link top.focess.veto.agent.AgentService} sets it from {@code
     * veto.security.screening-mode}.
     */
    private ScreeningMode screeningMode = ScreeningMode.STRICT;

    /**
     * The agent's workspace (needed to canonicalize path args for grant matching). Set by {@link
     * top.focess.veto.agent.AgentService}.
     */
    private volatile Workspace workspace;

    /** Pending veto futures keyed by {@code agentId + "|" + callId}. */
    private final ConcurrentHashMap<String, CompletableFuture<InterceptResolution>> pending =
            new ConcurrentHashMap<>();

    /** Permission grants per agent (session-scoped; cleared on terminate). */
    private final ConcurrentHashMap<String, Set<PermissionGrant>> grants =
            new ConcurrentHashMap<>();

    /** Audit log of every grant created (read grants, write grants, command grants) per agent. */
    private final ConcurrentHashMap<String, List<PermissionGrant>> grantLog =
            new ConcurrentHashMap<>();

    // ── Decide ──────────────────────────────────────────────────────────────

    /**
     * Decides an outcome from the {@link GatewayResult}, applying the {@link ScreeningMode} matrix
     * + permission grants for the given agent. NotScreened (agent tools) → AutoApprove; a {@link
     * GatewayResult.DriftResult} → a {@code WRITE_DRIFT} Prompt; a {@link GatewayResult.Screened}
     * result is resolved via {@code screeningMode.cell(relevance, danger)} — APPROVE → AutoApprove,
     * REFUSED → {@link ApprovalDecision.Refused} (CRITICAL only, no approval path), ASK →
     * AutoApprove on a matching grant, else {@link ApprovalDecision.Prompt} with the tool-declared
     * option set.
     */
    public ApprovalDecision decide(
            String agentId, ToolCall call, ToolDefinition def, GatewayResult result) {
        if (result instanceof GatewayResult.NotScreened) {
            return ApprovalDecision.AUTO_APPROVE;
        }
        if (result instanceof GatewayResult.DriftResult d) {
            return new ApprovalDecision.Prompt(VetoScenario.WRITE_DRIFT, W_OPTIONS);
        }
        GatewayResult.Screened s = (GatewayResult.Screened) result;
        Screening screening = s.screening();
        ScreeningOutcome outcome = screeningMode.cell(screening.relevance(), screening.danger());
        return switch (outcome) {
            case APPROVE -> ApprovalDecision.AUTO_APPROVE;
            case REFUSED ->
                    new ApprovalDecision.Refused(
                            "The agent is attempting a dangerous/refused action: ["
                                    + call.toolName()
                                    + (call.args() != null && !call.args().isEmpty()
                                            ? " " + call.args()
                                            : "")
                                    + "]. Reprompt to steer the LLM away from this. If you"
                                    + " believe this is a mis-classification, contact your"
                                    + " administrator.");
            case ASK -> {
                VetoScenario scenario = scenarioFor(call, def, screening);
                if (grantCovers(agentId, call, def, screening)) {
                    yield ApprovalDecision.AUTO_APPROVE;
                }
                yield new ApprovalDecision.Prompt(scenario, optionsFor(scenario));
            }
        };
    }

    /** Sets the screening matrix (called by {@link top.focess.veto.agent.AgentService}). */
    public void setScreeningMode(ScreeningMode screeningMode) {
        this.screeningMode = screeningMode;
    }

    /** Sets the workspace (called by {@link top.focess.veto.agent.AgentService}). */
    public void setWorkspace(Workspace workspace) {
        this.workspace = workspace;
    }

    // ── Tool-declared scenarios + option sets (screening_model.md §8) ────────

    /**
     * Maps a call + tool + screening → {@link VetoScenario}. Read tools → READ; write tools → WRITE
     * (or WRITE_DRIFT handled earlier); shell-exec/network → EXEC_DETERMINISTIC / EXEC_SEMANTIC /
     * EXEC_FIRST_TIME based on danger; external MCP tools → GENERIC.
     */
    public VetoScenario scenarioFor(ToolCall call, ToolDefinition def, Screening screening) {
        if (def == null) {
            return VetoScenario.GENERIC;
        }
        if (def instanceof AgentToolDefinition) {
            return VetoScenario.GENERIC;
        }
        RiskCategory risk = def.risk();
        if (risk == RiskCategory.READ_ONLY) {
            return VetoScenario.READ;
        }
        if (risk == RiskCategory.FILE_WRITE) {
            return VetoScenario.WRITE;
        }
        if (risk == RiskCategory.SHELL_EXEC || risk == RiskCategory.NETWORK) {
            if (screening.danger() == Danger.CRITICAL) {
                return VetoScenario.EXEC_DETERMINISTIC;
            }
            if (screening.danger() == Danger.DANGEROUS) {
                return VetoScenario.EXEC_SEMANTIC;
            }
            return VetoScenario.EXEC_FIRST_TIME;
        }
        return VetoScenario.GENERIC;
    }

    /**
     * The option set for a {@link VetoScenario} (screening_model.md §8). R/W/E1/E2/E3/generic per
     * the LLD table.
     */
    public List<VetoOption> optionsFor(VetoScenario scenario) {
        return switch (scenario) {
            case READ -> R_OPTIONS;
            case WRITE -> W_OPTIONS;
            case WRITE_DRIFT -> DRIFT_OPTIONS;
            case EXEC_DETERMINISTIC -> E1_OPTIONS;
            case EXEC_SEMANTIC -> E2_OPTIONS;
            case EXEC_FIRST_TIME -> E3_OPTIONS;
            case GENERIC -> GENERIC_OPTIONS;
        };
    }

    // Per-scenario option sets (screening_model.md §8). Constants avoid per-call allocation.
    static final List<VetoOption> R_OPTIONS =
            List.of(
                    VetoOption.ACCEPT_AND_MASK_READ,
                    VetoOption.ACCEPT_AND_MASK_READ_LIKE_THIS,
                    VetoOption.ACCEPT_READ,
                    VetoOption.ACCEPT_READ_LIKE_THIS,
                    VetoOption.READ_DECLINE);
    static final List<VetoOption> W_OPTIONS =
            List.of(
                    VetoOption.ACCEPT_AND_MASK_WRITE,
                    VetoOption.ACCEPT_AND_MASK_WRITE_LIKE_THIS,
                    VetoOption.ACCEPT_WRITE,
                    VetoOption.ACCEPT_WRITE_LIKE_THIS,
                    VetoOption.EXEC_DECLINE);
    static final List<VetoOption> DRIFT_OPTIONS =
            List.of(
                    VetoOption.ABORT_WRITE,
                    VetoOption.REREAD,
                    VetoOption.FORCE_OVERWRITE,
                    VetoOption.EDIT);
    static final List<VetoOption> E1_OPTIONS = List.of(VetoOption.BLOCK, VetoOption.OVERRIDE);
    static final List<VetoOption> E2_OPTIONS =
            List.of(
                    VetoOption.ACCEPT_AND_MASK_COMMAND,
                    VetoOption.ACCEPT_AND_MASK_COMMAND_LIKE_THIS,
                    VetoOption.ACCEPT_COMMAND,
                    VetoOption.ACCEPT_COMMAND_LIKE_THIS,
                    VetoOption.EXEC_DECLINE);
    static final List<VetoOption> E3_OPTIONS =
            List.of(
                    VetoOption.ACCEPT_COMMAND_ONCE,
                    VetoOption.ACCEPT_COMMAND_AS_SESSION_RULE,
                    VetoOption.EXEC_DECLINE);
    static final List<VetoOption> GENERIC_OPTIONS =
            List.of(
                    VetoOption.ACCEPT_GENERIC,
                    VetoOption.ACCEPT_GENERIC_LIKE_THIS,
                    VetoOption.GENERIC_DECLINE);

    // ── Grant matching (screening_model.md §7.1) ────────────────────────────

    /**
     * Returns true if any session grant for this agent covers the call's match key AND the
     * deterministic floor passes (the floor re-runs for every call — secret/protected paths refuse
     * even with a grant, §7.2 #3).
     */
    private boolean grantCovers(
            String agentId, ToolCall call, ToolDefinition def, Screening screening) {
        if (screening.danger() == Danger.CRITICAL) {
            return false; // CRITICAL is grant-immune
        }
        Set<PermissionGrant> agentGrants = grants.get(agentId);
        if (agentGrants == null || agentGrants.isEmpty()) {
            return false;
        }
        PermissionGrant.ToolCallSpec spec = MatchKeyExtractor.extract(call, def, workspace);
        for (PermissionGrant g : agentGrants) {
            if (g.matches(spec)) {
                return true;
            }
        }
        return false;
    }

    // ── Park / resolve ──────────────────────────────────────────────────────

    /**
     * Registers a pending veto future keyed by {@code (agentId, callId)} and returns it. The
     * agent's virtual thread parks on it.
     */
    public CompletableFuture<InterceptResolution> register(String agentId, String callId) {
        CompletableFuture<InterceptResolution> future = new CompletableFuture<>();
        pending.put(key(agentId, callId), future);
        return future;
    }

    /** Parks the calling (virtual) thread until the user resolves the veto. */
    public InterceptResolution await(String agentId, String callId) {
        CompletableFuture<InterceptResolution> future = pending.get(key(agentId, callId));
        if (future == null) {
            throw new IllegalStateException("no pending veto for " + agentId + "/" + callId);
        }
        try {
            return future.join();
        } finally {
            pending.remove(key(agentId, callId));
        }
    }

    /**
     * Resolves a pending veto. On {@code _LIKE_THIS} options, builds the appropriate {@link
     * PermissionGrant} (read / write / command) and caches it session-scoped. On {@link
     * VetoOption#ACCEPT_COMMAND_AS_SESSION_RULE} / {@link VetoOption#ACCEPT_AS_SESSION_RULE} legacy
     * alias, builds a command grant with the same match key. Returns {@code false} if no pending
     * veto exists for the key.
     */
    public boolean resolve(
            String agentId,
            String callId,
            InterceptResolution resolution,
            ToolCall originalCall,
            ToolDefinition originalDef) {
        CompletableFuture<InterceptResolution> future = pending.remove(key(agentId, callId));
        if (future == null) {
            return false;
        }
        if (resolution.createsGrant() && originalCall != null && originalDef != null) {
            PermissionGrant grant = buildGrant(originalCall, originalDef, resolution);
            if (grant != null) {
                grants.computeIfAbsent(agentId, k -> ConcurrentHashMap.newKeySet()).add(grant);
                grantLog.computeIfAbsent(
                                agentId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                        .add(grant);
            }
        }
        future.complete(resolution);
        return true;
    }

    /**
     * Builds a {@link PermissionGrant} for the given call + resolution. Reads → ReadGrant (dir
     * prefix + read tool family); writes → WriteGrant (dir prefix + tool); exec → CommandGrant
     * (executable + subcommand + flag shape). The grant is keyed on the call's canonicalized path
     * arg or the first command's executable+subcommands.
     */
    PermissionGrant buildGrant(ToolCall call, ToolDefinition def, InterceptResolution resolution) {
        VetoOption opt = resolution.option();
        if (def == null) {
            return null;
        }
        // Generic grant (no per-tool match key): ACCEPT_GENERIC_LIKE_THIS / ACCEPT_AS_SESSION_RULE
        // legacy alias.
        if (opt == VetoOption.ACCEPT_GENERIC_LIKE_THIS
                || opt == VetoOption.ACCEPT_AS_SESSION_RULE) {
            // Synthesize a generic command-equivalent grant from the call's args if run_command.
            if ("run_command".equals(call.toolName())) {
                return buildCommandGrant(call);
            }
            // Fall back to a per-tool record grant (legacy "session rule" semantics).
            Map<String, Object> args =
                    resolution.editedArgs() != null ? resolution.editedArgs() : call.args();
            return new PermissionGrant.LegacySessionRule(call.toolName(), args);
        }
        // Per-tool shape: read / write / command.
        if (def.risk() == RiskCategory.READ_ONLY) {
            return buildReadGrant(call, def);
        }
        if (def.risk() == RiskCategory.FILE_WRITE) {
            return buildWriteGrant(call, def);
        }
        if (def.risk() == RiskCategory.SHELL_EXEC || def.risk() == RiskCategory.NETWORK) {
            return buildCommandGrant(call);
        }
        return null;
    }

    private PermissionGrant.ReadGrant buildReadGrant(ToolCall call, ToolDefinition def) {
        Path dir = directoryOfFirstPathArg(call, def);
        List<String> flagShape = MatchKeyExtractor.extract(call, def, workspace).flagShape();
        String family = "read";
        return new PermissionGrant.ReadGrant(family, dir == null ? Path.of(".") : dir, flagShape);
    }

    private PermissionGrant.WriteGrant buildWriteGrant(ToolCall call, ToolDefinition def) {
        Path dir = directoryOfFirstPathArg(call, def);
        List<String> flagShape = MatchKeyExtractor.extract(call, def, workspace).flagShape();
        return new PermissionGrant.WriteGrant(
                call.toolName(), dir == null ? Path.of(".") : dir, flagShape);
    }

    private PermissionGrant.CommandGrant buildCommandGrant(ToolCall call) {
        Map<String, Object> args = call.args();
        if (args == null) {
            return new PermissionGrant.CommandGrant("", List.of(), List.of());
        }
        Object commandsObj = args.get("commands");
        if (!(commandsObj instanceof List<?> commands) || commands.isEmpty()) {
            return new PermissionGrant.CommandGrant("", List.of(), List.of());
        }
        Object first = commands.get(0);
        if (!(first instanceof Map<?, ?> cmd)) {
            return new PermissionGrant.CommandGrant("", List.of(), List.of());
        }
        Object execObj = cmd.get("executable");
        Object cmdArgsObj = cmd.get("args");
        String executable = execObj == null ? "" : execObj.toString();
        List<String> subcommands = new ArrayList<>();
        if (cmdArgsObj instanceof List<?> cmdArgs) {
            for (Object a : cmdArgs) {
                if (a instanceof String s && !s.startsWith("-")) {
                    subcommands.add(s);
                } else {
                    break; // flags / values end the leading-subcommand span
                }
            }
        }
        List<String> flagShape = MatchKeyExtractor.extract(call, null, workspace).flagShape();
        return new PermissionGrant.CommandGrant(executable, subcommands, flagShape);
    }

    private Path directoryOfFirstPathArg(ToolCall call, ToolDefinition def) {
        if (def == null || workspace == null) {
            return null;
        }
        Map<String, top.focess.veto.agent.mcp.ParamCategory> hints = paramHints(def);
        if (hints.isEmpty()) {
            return null;
        }
        Map<String, Object> args = call.args();
        if (args == null) {
            return null;
        }
        for (var entry : hints.entrySet()) {
            if (entry.getValue() != top.focess.veto.agent.mcp.ParamCategory.FILESYSTEM_PATH) {
                continue;
            }
            Object v = args.get(entry.getKey());
            if (!(v instanceof String s) || s.isBlank()) {
                continue;
            }
            try {
                top.focess.veto.agent.workspace.Resolution res =
                        workspace.pathResolver().resolveToHost(s);
                Path host =
                        res.inScope() ? res.hostPath() : Path.of(s).toAbsolutePath().normalize();
                Path parent = host.getParent();
                return parent == null ? host : parent;
            } catch (RuntimeException e) {
                Path host = Path.of(s).toAbsolutePath().normalize();
                Path parent = host.getParent();
                return parent == null ? host : parent;
            }
        }
        return null;
    }

    private static Map<String, top.focess.veto.agent.mcp.ParamCategory> paramHints(
            ToolDefinition def) {
        if (def instanceof NativeToolDefinition n) {
            return n.paramHints();
        }
        if (def instanceof AgentToolDefinition a) {
            return a.paramHints();
        }
        return Map.of();
    }

    /**
     * Decline every pending veto for the agent (on session stop / new-prompt-while-held = decline).
     * The agent loop will synthesize the {@code ToolResponse(REFUSED)} observations.
     */
    public void declineAll(String agentId) {
        pending.forEach(
                (k, future) -> {
                    if (k.startsWith(agentId + "|")) {
                        future.complete(new InterceptResolution(VetoOption.EXEC_DECLINE, null));
                    }
                });
    }

    /** Drops any pending veto + grants for the agent (on terminate). */
    public void clear(String agentId) {
        pending.keySet().removeIf(k -> k.startsWith(agentId + "|"));
        grants.remove(agentId);
        grantLog.remove(agentId);
    }

    /** Revoke a single grant (audited + revocable, screening_model.md §7.2 #5). */
    public boolean revokeGrant(String agentId, PermissionGrant grant) {
        Set<PermissionGrant> agentGrants = grants.get(agentId);
        if (agentGrants == null) {
            return false;
        }
        return agentGrants.remove(grant);
    }

    /** Returns the audit log of grants created for the agent (screening_model.md §7.2 #5). */
    public List<PermissionGrant> grantLog(String agentId) {
        List<PermissionGrant> log = grantLog.get(agentId);
        return log == null ? List.of() : List.copyOf(log);
    }

    private static String key(String agentId, String callId) {
        return agentId + "|" + callId;
    }
}
