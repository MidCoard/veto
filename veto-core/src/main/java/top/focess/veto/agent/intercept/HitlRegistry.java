package top.focess.veto.agent.intercept;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.AgentService;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.screening.Screening;
import top.focess.veto.agent.screening.ScreeningMode;
import top.focess.veto.agent.screening.ScreeningOutcome;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.i18n.Msg;
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
     * ScreeningMode#STRICT}; {@link AgentService} sets it from {@code
     * veto.security.screening-mode}.
     */
    private ScreeningMode screeningMode = ScreeningMode.STRICT;

    /**
     * Per-agent workspaces (needed to canonicalize path args for grant matching). Registered by
     * {@link AgentService} under the agent's {@code persona.id()} when the agent is created, so
     * each session's grants match against its own workspace - not a process-global one. Cleared by
     * {@link #clear}.
     */
    private final ConcurrentHashMap<String, Workspace> workspaces = new ConcurrentHashMap<>();

    /**
     * The fallback workspace for agents without a registered entry (e.g. a legacy call path that
     * did not flow through {@link AgentService#createAgent}). Set by {@link AgentService} from the
     * process-wide {@code veto.workspace.path-mode} + the JVM working dir.
     */
    private volatile Workspace defaultWorkspace;

    /**
     * A pending veto: the future the agent parks on, plus the original call/def/offered-options
     * stashed at {@link #register} time so {@link #resolve} can build a grant without the resolver
     * supplying them (the transport cannot reach a {@link ToolDefinition}).
     */
    record Pending(
            CompletableFuture<InterceptResolution> future,
            ToolCall call,
            ToolDefinition def,
            List<VetoOption> options) {}

    /** Pending veto futures keyed by {@code agentId + "|" + callId}. */
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    /** Permission grants per agent (session-scoped; cleared on terminate). */
    private final ConcurrentHashMap<String, Set<PermissionGrant>> grants =
            new ConcurrentHashMap<>();

    /** Audit log of every grant created (read grants, write grants, command grants) per agent. */
    private final ConcurrentHashMap<String, List<PermissionGrant>> grantLog =
            new ConcurrentHashMap<>();

    /**
     * Per-agent message locale for user-facing refusal reasons (decide runs on the agent's virtual
     * thread, off the request thread). Stamped by {@code AgentRunner.setLocale}; English default.
     * Cleared by {@link #clear}.
     */
    private final ConcurrentHashMap<String, Locale> locales = new ConcurrentHashMap<>();

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
                            Msg.get(
                                    localeFor(agentId),
                                    "error.hitl.refusedCritical",
                                    call.toolName(),
                                    call.args() != null && !call.args().isEmpty()
                                            ? " " + call.args()
                                            : ""));
            case ASK -> {
                VetoScenario scenario = scenarioFor(call, def, screening);
                if (grantCovers(agentId, call, def, screening)) {
                    yield ApprovalDecision.AUTO_APPROVE;
                }
                yield new ApprovalDecision.Prompt(scenario, optionsFor(scenario));
            }
        };
    }

    /** Sets the screening matrix (called by {@link AgentService}). */
    public void setScreeningMode(@NonNull ScreeningMode screeningMode) {
        this.screeningMode = screeningMode;
    }

    /** Stamps the agent's message locale (called by {@code AgentRunner.setLocale}). */
    public void setLocale(@NonNull String agentId, @NonNull Locale locale) {
        locales.put(agentId, locale);
    }

    /** The agent's message locale, defaulting to English when none was stamped. */
    private @NonNull Locale localeFor(@NonNull String agentId) {
        return locales.getOrDefault(agentId, Locale.ENGLISH);
    }

    /**
     * Registers the workspace for an agent (called by {@link AgentService} under the agent's {@code
     * persona.id()} when the agent is created).
     */
    public void setWorkspace(@NonNull String agentId, @NonNull Workspace workspace) {
        workspaces.put(agentId, workspace);
    }

    /**
     * Sets the fallback workspace for agents without a registered entry (called by {@link
     * AgentService}).
     */
    public void setDefaultWorkspace(@NonNull Workspace workspace) {
        this.defaultWorkspace = workspace;
    }

    /**
     * The workspace registered for the agent, or the fallback default if none. Never null in
     * production ({@link AgentService} sets the default at startup); tests must set it via {@link
     * #setWorkspace(String, Workspace)} or {@link #setDefaultWorkspace}.
     */
    public @NonNull Workspace workspace(@NonNull String agentId) {
        Workspace ws = workspaces.get(agentId);
        return ws != null ? ws : defaultWorkspace;
    }

    // ── Tool-declared scenarios + option sets (screening_model.md §8) ────────

    /**
     * Maps a call + tool + screening → {@link VetoScenario}. Read tools → READ; write tools → WRITE
     * (or WRITE_DRIFT handled earlier); shell-exec/network → EXEC_DETERMINISTIC / EXEC_SEMANTIC /
     * EXEC_FIRST_TIME based on danger; external MCP tools → GENERIC.
     */
    public @NonNull VetoScenario scenarioFor(
            @NonNull ToolCall call, @NonNull ToolDefinition def, @NonNull Screening screening) {
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
    public @NonNull List<VetoOption> optionsFor(@NonNull VetoScenario scenario) {
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
    // Write offers no mask variants: masking protects content the agent READS back (secrets
    // entering the model's context); a write's observation carries no user content worth
    // scrubbing, so the choice is just accept / accept-like-this / decline.
    static final List<VetoOption> W_OPTIONS =
            List.of(
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
    // Exec offers no mask variants (1.0.72): per the user's HITL principle, the explicit
    // accept_and_mask choice belongs to READ only; an exec observation's masking is the
    // IngressDefense default-on behavior, not a per-veto user choice. The ACCEPT_AND_MASK_COMMAND*
    // enum constants are retained for backward compatibility.
    static final List<VetoOption> E2_OPTIONS =
            List.of(
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
        PermissionGrant.ToolCallSpec spec =
                MatchKeyExtractor.extract(call, def, workspace(agentId));
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
     * agent's virtual thread parks on it. No call/def/options are stashed (the Refused-park path
     * uses this - it is resolved via {@link #declineAll}, never builds a grant).
     */
    public @NonNull CompletableFuture<InterceptResolution> register(
            @NonNull String agentId, @NonNull String callId) {
        return register(agentId, callId, null, null, null);
    }

    /**
     * Registers a pending veto future with the original call/def/offered-options stashed for
     * grant-building. The agent's virtual thread parks on the returned future until {@link
     * #resolve} completes it. The stash lets {@link #resolve} build a grant without the resolver
     * supplying the call/def (the transport cannot reach a {@link ToolDefinition}).
     */
    public @NonNull CompletableFuture<InterceptResolution> register(
            @NonNull String agentId,
            @NonNull String callId,
            @Nullable ToolCall call,
            @Nullable ToolDefinition def,
            @Nullable List<VetoOption> options) {
        CompletableFuture<InterceptResolution> future = new CompletableFuture<>();
        pending.put(key(agentId, callId), new Pending(future, call, def, options));
        return future;
    }

    /** Parks the calling (virtual) thread until the user resolves the veto. */
    public @NonNull InterceptResolution await(@NonNull String agentId, @NonNull String callId) {
        Pending p = pending.get(key(agentId, callId));
        if (p == null) {
            throw new IllegalStateException("no pending veto for " + agentId + "/" + callId);
        }
        try {
            return p.future().join();
        } finally {
            pending.remove(key(agentId, callId));
        }
    }

    /**
     * Resolves a pending veto. On {@code _LIKE_THIS} options, builds the appropriate {@link
     * PermissionGrant} (read / write / command) and caches it session-scoped, using the call/def
     * stashed at {@link #register} time. Returns {@code false} if no pending veto exists for the
     * key.
     */
    public boolean resolve(
            @NonNull String agentId,
            @NonNull String callId,
            @NonNull InterceptResolution resolution) {
        Pending p = pending.remove(key(agentId, callId));
        if (p == null) {
            return false;
        }
        if (resolution.createsGrant() && p.call() != null && p.def() != null) {
            PermissionGrant grant = buildGrant(agentId, p.call(), p.def(), resolution);
            if (grant != null) {
                grants.computeIfAbsent(agentId, k -> ConcurrentHashMap.newKeySet()).add(grant);
                grantLog.computeIfAbsent(
                                agentId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                        .add(grant);
            }
        }
        p.future().complete(resolution);
        return true;
    }

    /**
     * Resolves a pending veto from a user-chosen option name (the {@code Input} reply). Validates
     * {@code optionName} against the offered options stashed at {@link #register}; on a valid
     * choice, builds the {@link InterceptResolution} (masking per {@link
     * VetoOption#impliesMasking()}) and resolves. On an invalid choice (defense-in-depth - the
     * terminal validates client-side), resolves with the scenario's refusal so the agent unstucks
     * fail-safe rather than executing a mis-approved call. Returns {@code false} only if no pending
     * veto exists.
     */
    public boolean resolveOption(
            @NonNull String agentId, @NonNull String callId, @NonNull String optionName) {
        Pending p = pending.get(key(agentId, callId));
        if (p == null) {
            return false;
        }
        VetoOption chosen = parseOption(optionName, p.options());
        InterceptResolution resolution =
                chosen != null
                        ? new InterceptResolution(chosen, null, chosen.impliesMasking())
                        : new InterceptResolution(firstRefusal(p.options()), null);
        return resolve(agentId, callId, resolution);
    }

    /**
     * Declines a pending veto (cancel-during-veto): resolves with the scenario's refusal option so
     * the agent refuses this call and continues. Returns {@code false} if no pending veto exists.
     */
    public boolean declineOption(@NonNull String agentId, @NonNull String callId) {
        Pending p = pending.get(key(agentId, callId));
        if (p == null) {
            return false;
        }
        return resolve(agentId, callId, new InterceptResolution(firstRefusal(p.options()), null));
    }

    /** Parses the option name against the offered set (case-insensitive); null if not offered. */
    private static @Nullable VetoOption parseOption(
            @NonNull String optionName, @Nullable List<VetoOption> offered) {
        if (offered == null || offered.isEmpty()) {
            return null;
        }
        for (VetoOption o : offered) {
            if (o.name().equalsIgnoreCase(optionName)) {
                return o;
            }
        }
        return null;
    }

    /**
     * The first refusal option in the offered set, falling back to {@link VetoOption#EXEC_DECLINE}.
     */
    private static @NonNull VetoOption firstRefusal(@Nullable List<VetoOption> offered) {
        if (offered != null) {
            for (VetoOption o : offered) {
                if (o.isRefusal()) {
                    return o;
                }
            }
        }
        return VetoOption.EXEC_DECLINE;
    }

    /**
     * Builds a {@link PermissionGrant} for the given call + resolution. Reads → ReadGrant (dir
     * prefix + read tool family); writes → WriteGrant (dir prefix + tool); exec → CommandGrant
     * (executable + subcommand + flag shape). The grant is keyed on the call's canonicalized path
     * arg or the first command's executable+subcommands.
     */
    PermissionGrant buildGrant(
            String agentId, ToolCall call, ToolDefinition def, InterceptResolution resolution) {
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
                return buildCommandGrant(agentId, call);
            }
            // Fall back to a per-tool record grant (legacy "session rule" semantics).
            Map<String, Object> args =
                    resolution.editedArgs() != null ? resolution.editedArgs() : call.args();
            return new PermissionGrant.LegacySessionRule(call.toolName(), args);
        }
        // Per-tool shape: read / write / command.
        if (def.risk() == RiskCategory.READ_ONLY) {
            return buildReadGrant(agentId, call, def);
        }
        if (def.risk() == RiskCategory.FILE_WRITE) {
            return buildWriteGrant(agentId, call, def);
        }
        if (def.risk() == RiskCategory.SHELL_EXEC || def.risk() == RiskCategory.NETWORK) {
            return buildCommandGrant(agentId, call);
        }
        return null;
    }

    private PermissionGrant.ReadGrant buildReadGrant(
            String agentId, ToolCall call, ToolDefinition def) {
        Path dir = directoryOfFirstPathArg(agentId, call, def);
        List<String> flagShape =
                MatchKeyExtractor.extract(call, def, workspace(agentId)).flagShape();
        // Scope by the exact tool (like-this = same tool + directory subtree): a list_dir grant
        // must not auto-approve view_file / grep_search calls under the same prefix.
        return new PermissionGrant.ReadGrant(
                def.name(), dir == null ? Path.of(".") : dir, flagShape);
    }

    private PermissionGrant.WriteGrant buildWriteGrant(
            String agentId, ToolCall call, ToolDefinition def) {
        Path dir = directoryOfFirstPathArg(agentId, call, def);
        List<String> flagShape =
                MatchKeyExtractor.extract(call, def, workspace(agentId)).flagShape();
        return new PermissionGrant.WriteGrant(
                call.toolName(), dir == null ? Path.of(".") : dir, flagShape);
    }

    private PermissionGrant.CommandGrant buildCommandGrant(String agentId, ToolCall call) {
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
        List<String> flagShape =
                MatchKeyExtractor.extract(call, null, workspace(agentId)).flagShape();
        return new PermissionGrant.CommandGrant(executable, subcommands, flagShape);
    }

    private Path directoryOfFirstPathArg(String agentId, ToolCall call, ToolDefinition def) {
        Workspace ws = workspace(agentId);
        if (def == null || ws == null) {
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
                top.focess.veto.agent.workspace.Resolution res = ws.pathResolver().resolveToHost(s);
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
     * A transport-facing view of the agent's pending vetoes: the parked call + the option names
     * offered to the user. Entries without a stashed call (the Refused-park path) are skipped -
     * they resolve via {@link #declineAll}, never through a picker.
     */
    public @NonNull List<Map<String, Object>> pendingFor(@NonNull String agentId) {
        List<Map<String, Object>> out = new ArrayList<>();
        String prefix = agentId + "|";
        pending.forEach(
                (k, p) -> {
                    if (!k.startsWith(prefix) || p.call() == null) {
                        return;
                    }
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("callId", p.call().callId());
                    view.put("toolName", p.call().toolName());
                    view.put("args", p.call().args() != null ? p.call().args() : Map.of());
                    view.put(
                            "options",
                            p.options() != null
                                    ? p.options().stream().map(VetoOption::name).toList()
                                    : List.<String>of());
                    out.add(view);
                });
        return out;
    }

    /**
     * Decline every pending veto for the agent (on session stop / new-prompt-while-held = decline /
     * a transport's cancel). The agent loop will synthesize the {@code ToolResponse(REFUSED)}
     * observations. Returns the number of vetoes declined.
     */
    public int declineAll(@NonNull String agentId) {
        int[] declined = {0};
        pending.forEach(
                (k, p) -> {
                    if (k.startsWith(agentId + "|")
                            && p.future()
                                    .complete(
                                            new InterceptResolution(
                                                    VetoOption.EXEC_DECLINE, null))) {
                        declined[0]++;
                    }
                });
        return declined[0];
    }

    /** Drops any pending veto + grants for the agent (on terminate). */
    public void clear(@NonNull String agentId) {
        pending.keySet().removeIf(k -> k.startsWith(agentId + "|"));
        grants.remove(agentId);
        grantLog.remove(agentId);
        workspaces.remove(agentId);
        locales.remove(agentId);
    }

    /** Revoke a single grant (audited + revocable, screening_model.md §7.2 #5). */
    public boolean revokeGrant(@NonNull String agentId, @NonNull PermissionGrant grant) {
        Set<PermissionGrant> agentGrants = grants.get(agentId);
        if (agentGrants == null) {
            return false;
        }
        return agentGrants.remove(grant);
    }

    /** Returns the audit log of grants created for the agent (screening_model.md §7.2 #5). */
    public @NonNull List<PermissionGrant> grantLog(@NonNull String agentId) {
        List<PermissionGrant> log = grantLog.get(agentId);
        return log == null ? List.of() : List.copyOf(log);
    }

    private static String key(String agentId, String callId) {
        return agentId + "|" + callId;
    }
}
