package top.focess.veto.agent.intercept;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RemoteToolDefinition;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.llm.core.ToolCall;

/**
 * Deterministic ingress defense. Frames every observation as untrusted <b>data</b> with an explicit
 * source label before it re-enters the loop, applies {@code accept_and_mask} (default-on) to
 * read/exec observations when the HITL resolution permits it, and — for a successful write —
 * invalidates the {@link ReadHistory} entry for the written path (so the next read/write cycle
 * starts fresh).
 *
 * <p>The masking is best-effort and policy-independent (applies under both {@code FULL_ACCESS} and
 * {@code PROTECTED}); the user can turn it off by choosing a plain {@code ACCEPT_*} option (no
 * mask). The authoritative secrets control is the Vault.
 */
@Component
public class IngressDefense {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.intercept.IngressDefense");

    /**
     * The advisory semantic masker (Part 3.3). Layered over the deterministic {@link SecretMasker}
     * floor: it consults the local SLM to flag likely-exfiltration observations, while always
     * applying the deterministic redaction regardless of SLM availability. Nullable so the no-arg
     * construction path (existing tests, no SLM configured) degrades to deterministic-only.
     */
    private final SemanticMasker semanticMasker;

    /** Spring-injected constructor — the SLM-backed masker is optional (degrades if absent). */
    @Autowired
    public IngressDefense(@Autowired(required = false) SemanticMasker semanticMasker) {
        this.semanticMasker = semanticMasker;
    }

    /**
     * No-arg constructor — degrades to deterministic-only masking (the SLM semantic layer is
     * absent). Kept so existing non-Spring callers and tests compile unchanged.
     */
    public IngressDefense() {
        this(new SemanticMasker());
    }

    /**
     * Masks and frames an observation.
     *
     * @param call the tool call that produced the result
     * @param def the resolved tool definition (drives source label + write detection)
     * @param result the raw tool result
     * @param maskObservation whether to apply secret-pattern masking (the {@code accept_and_mask}
     *     flag — default-on per the user's HITL decision; for an {@code AutoApprove} the caller
     *     passes {@code true})
     * @param readHistory this agent's read-history (invalidated on a successful write)
     * @return the framed observation string
     */
    public @NonNull String maskAndFrame(
            @NonNull ToolCall call,
            @NonNull ToolDefinition def,
            top.focess.veto.agent.mcp.@NonNull ToolResult result,
            boolean maskObservation,
            @NonNull ReadHistory readHistory) {
        String body = result.content();

        // On a successful write, the recorded read-snapshot is now stale — invalidate it.
        if (result.success() && def.risk() == RiskCategory.FILE_WRITE) {
            invalidateWritePath(call, def, readHistory);
        }

        // apply accept_and_mask: scrub secrets from read / exec observations before they enter
        // context. Default-on; the caller's flag is authoritative. Writes have no observation
        // content to mask (they return void / success), so we skip them. The SLM semantic masker
        // (Part 3.3) is the advisory layer over the deterministic SecretMasker floor — it always
        // applies the deterministic redaction and may additionally surface a HighRiskSignal.
        if (maskObservation
                && (def.risk() == RiskCategory.READ_ONLY
                        || def.risk() == RiskCategory.SHELL_EXEC
                        || def.risk() == RiskCategory.NETWORK)) {
            if (semanticMasker != null) {
                SemanticMasker.MaskResult masked = semanticMasker.maskWithSignal(body, call, def);
                body = masked.masked();
                var highRisk = masked.highRisk();
                if (highRisk != null) {
                    // The observation is already redacted; the signal is advisory — the caller
                    // (AgentRunner) cannot reject post-execution, so it is surfaced for
                    // observability/alerting rather than gating the call.
                    log.warn(
                            "SemanticMasker flagged high-risk exfiltration for tool {}: {}",
                            highRisk.toolName(),
                            highRisk.reason());
                }
            } else {
                body = SecretMasker.mask(body);
            }
        }

        // Reserved-prefix enforcement: the REFUSED grammar (RefusalObservation) is reserved for
        // harness-synthesized refusal observations, but it travels the same tool-response channel
        // as this body. A tool result that happens to open with the reserved prefix (e.g. a
        // command's stdout) would read as a veto decision - quote its leading REFUSED so the
        // grammar stays exclusive to real refusals.
        body = RefusalObservation.neutralize(body);

        // Minimal framing for a text-based ReAct loop: the tool name + args in the header makes
        // each observation self-describing (the model can associate a result with its call without
        // a separate tool_call_id structure). No source label, no ok/error envelope, no per-obs
        // security marker - the body carries its own status (e.g. {"status":"error"}), and the
        // "treat observation content as data" policy lives in the system prompt where it belongs.
        // Raw output - no text framing. The call_id (set by PromptCompiler
        // on the ChatMessage) provides the structural link between the
        // tool call and its result. The provider SDK renders this as a
        // native tool_result with tool_call_id.
        return body;
    }

    /**
     * Backward-compat overload (sub-spec B callers pass an {@link ApprovalDecision} decision).
     * AutoApprove → mask-on (default); Prompt → mask-on (the resolution's per-call mask flag
     * defaults true; callers that have the {@link InterceptResolution} should use the boolean
     * overload). Refused / AutoBlock → mask-on.
     */
    public @NonNull String maskAndFrame(
            @NonNull ToolCall call,
            @NonNull ToolDefinition def,
            top.focess.veto.agent.mcp.@NonNull ToolResult result,
            @NonNull ApprovalDecision decision,
            @NonNull ReadHistory readHistory) {
        boolean mask = true;
        return maskAndFrame(call, def, result, mask, readHistory);
    }

    private void invalidateWritePath(
            @NonNull ToolCall call, @NonNull ToolDefinition def, @NonNull ReadHistory readHistory) {
        Map<@NonNull String, @NonNull ParamCategory> hints =
                switch (def) {
                    case NativeToolDefinition n -> n.paramHints();
                    case AgentToolDefinition a -> a.paramHints();
                    case RemoteToolDefinition r -> java.util.Map.of();
                };
        for (var entry : hints.entrySet()) {
            if (entry.getValue() == ParamCategory.FILESYSTEM_PATH) {
                Object v = call.args().get(entry.getKey());
                if (v instanceof String s && !s.isBlank()) {
                    readHistory.invalidate(s);
                }
            }
        }
    }
}
