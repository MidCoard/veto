package top.focess.veto.agent.intercept;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.llm.core.ToolCall;

/**
 * The single human-in-the-loop registry. Owns three responsibilities:
 *
 * <ol>
 *   <li>{@link #decide} — apply the auto-approval policy + Session Rules to a {@link Verdict} →
 *       {@link ApprovalDecision}. READ_ONLY + no flag → auto-proceed; a matching Session Rule →
 *       auto-approved (still re-checked: {@link Verdict.Blocked} is never approved).
 *   <li>{@link #register} / {@link #await} — park the agent's virtual thread on a {@link
 *       CompletableFuture} keyed by {@code (agentId, callId)} (the Loom yield).
 *   <li>{@link #resolve} — complete the future with the user's {@link InterceptResolution}; on
 *       {@link VetoOption#ACCEPT_AS_SESSION_RULE} cache the approved pattern as an ephemeral
 *       session rule.
 * </ol>
 *
 * <p>This is <b>not</b> the {@code LoopInterceptor} plugin chain — HITL is a dedicated mechanism,
 * not signaled through plugins. {@code agentId} is passed explicitly so Session Rules are scoped
 * per-agent without a thread-local indirection.
 */
@Component
public class HitlRegistry {

    /** Pending veto futures keyed by {@code agentId + "|" + callId}. */
    private final ConcurrentHashMap<String, CompletableFuture<InterceptResolution>> pending =
            new ConcurrentHashMap<>();

    /** Session rules (approved-pattern cache) per agent: {@code agentId → set of (tool,args)}. */
    private final ConcurrentHashMap<String, Set<Rule>> sessionRules = new ConcurrentHashMap<>();

    // ── Decide ──────────────────────────────────────────────────────────────

    /**
     * Decides an outcome from the Gateway's verdict, applying the auto-approval policy + Session
     * Rules for the given agent.
     */
    public ApprovalDecision decide(
            String agentId, ToolCall call, ToolDefinition def, Verdict verdict) {
        // Blocked is never auto-approved, regardless of session rules.
        if (verdict instanceof Verdict.Blocked b) {
            return new ApprovalDecision.AutoBlock(b.reason());
        }
        if (verdict instanceof Verdict.Drift) {
            return new ApprovalDecision.Prompt(
                    VetoScenario.WRITE_DRIFT,
                    List.of(
                            VetoOption.ABORT_WRITE,
                            VetoOption.REREAD,
                            VetoOption.FORCE_OVERWRITE,
                            VetoOption.EDIT),
                    verdict);
        }
        if (verdict instanceof Verdict.Risky r) {
            if (matchesSessionRule(agentId, call)) {
                return ApprovalDecision.AUTO_APPROVE;
            }
            return new ApprovalDecision.Prompt(r.scenario(), optionsFor(r.scenario()), verdict);
        }
        // Verdict.SAFE
        if (def.risk() == RiskCategory.READ_ONLY) {
            return ApprovalDecision.AUTO_APPROVE;
        }
        if (matchesSessionRule(agentId, call)) {
            return ApprovalDecision.AUTO_APPROVE;
        }
        // gap (noted): the SLM screening model that would produce a proper write/exec/network
        // scenario for an otherwise-clean state-changing call is Phase-2. Under a
        // state-changing/networked call without a rule still parks; offered a generic set.
        return new ApprovalDecision.Prompt(
                VetoScenario.GENERIC, optionsFor(VetoScenario.GENERIC), verdict);
    }

    private List<VetoOption> optionsFor(VetoScenario scenario) {
        return switch (scenario) {
            case READ -> List.of(VetoOption.READ_MASK, VetoOption.READ_ALLOW, VetoOption.READ_DENY);
            case WRITE_DRIFT ->
                    List.of(
                            VetoOption.ABORT_WRITE,
                            VetoOption.REREAD,
                            VetoOption.FORCE_OVERWRITE,
                            VetoOption.EDIT);
            case EXEC_DETERMINISTIC -> List.of(VetoOption.BLOCK, VetoOption.OVERRIDE);
            case EXEC_SEMANTIC ->
                    List.of(VetoOption.ACCEPT, VetoOption.ACCEPT_REDACTED, VetoOption.DECLINE);
            case EXEC_FIRST_TIME ->
                    List.of(
                            VetoOption.ACCEPT_ONCE,
                            VetoOption.ACCEPT_AS_SESSION_RULE,
                            VetoOption.DECLINE);
            case GENERIC -> List.of(VetoOption.ACCEPT, VetoOption.EDIT, VetoOption.DECLINE);
        };
    }

    private boolean matchesSessionRule(String agentId, ToolCall call) {
        Set<Rule> rules = sessionRules.get(agentId);
        return rules != null && rules.contains(new Rule(call.toolName(), call.args()));
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
     * Resolves a pending veto. On {@link VetoOption#ACCEPT_AS_SESSION_RULE} the {@code (tool,
     * args)} pattern is cached as an ephemeral session rule. Returns {@code false} if no pending
     * veto exists for the key.
     */
    public boolean resolve(
            String agentId, String callId, InterceptResolution resolution, String toolName) {
        CompletableFuture<InterceptResolution> future = pending.remove(key(agentId, callId));
        if (future == null) {
            return false;
        }
        if (resolution.option() == VetoOption.ACCEPT_AS_SESSION_RULE) {
            sessionRules
                    .computeIfAbsent(agentId, k -> ConcurrentHashMap.newKeySet())
                    .add(new Rule(toolName, resolution.editedArgs()));
        }
        future.complete(resolution);
        return true;
    }

    /** Drops any pending veto + session rules for the agent (on terminate). */
    public void clear(String agentId) {
        pending.keySet().removeIf(k -> k.startsWith(agentId + "|"));
        sessionRules.remove(agentId);
    }

    private static String key(String agentId, String callId) {
        return agentId + "|" + callId;
    }

    /** A session rule: identical-match on {@code (toolName, args)}. */
    private record Rule(String toolName, Map<String, Object> args) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Rule r)) {
                return false;
            }
            return Objects.equals(toolName, r.toolName) && Objects.equals(args, r.args);
        }

        @Override
        public int hashCode() {
            return Objects.hash(toolName, args);
        }
    }
}
