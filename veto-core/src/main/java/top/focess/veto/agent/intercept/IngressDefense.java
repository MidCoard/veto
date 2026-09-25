package top.focess.veto.agent.intercept;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.tool.AgentToolDefinition;
import top.focess.veto.agent.tool.ExecutionReceipts;
import top.focess.veto.agent.tool.NativeToolDefinition;
import top.focess.veto.agent.tool.RemoteToolDefinition;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolResult;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.util.Nullness;

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
    private static final @NonNull ObjectMapper JSON = new ObjectMapper();

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.intercept.IngressDefense");

    /**
     * The advisory semantic masker, layered over the session-less {@code
     * veto:observation-middleware} floor. It consults the local SLM to flag likely-exfiltration
     * observations while always applying the plugin's redaction regardless of SLM availability.
     * Nullable so the no-arg construction path (existing tests, no SLM configured) degrades to the
     * floor only.
     */
    private final SemanticMasker semanticMasker;

    /**
     * The session-less observation-masking floor ({@link
     * PluginManager#applyObservationMiddleware}); identity when no plugin manager is bound
     * (detached construction). The floor is provided by the secret-protection plugin, which ships
     * on the runtime classpath by default.
     */
    private final @NonNull UnaryOperator<@NonNull String> observationFloor;

    /** Spring-injected constructor — the SLM-backed masker is optional (degrades if absent). */
    @Autowired
    public IngressDefense(
            @Autowired(required = false) SemanticMasker semanticMasker,
            @NonNull ObjectProvider<PluginManager> plugins) {
        this(semanticMasker, plugins.getIfAvailable());
    }

    /**
     * No-arg constructor — degrades to deterministic-only masking (the SLM semantic layer is
     * absent) with an identity floor. Kept so existing non-Spring callers and tests compile
     * unchanged.
     */
    public IngressDefense() {
        this(new SemanticMasker(), (PluginManager) null);
    }

    private IngressDefense(SemanticMasker semanticMasker, PluginManager plugins) {
        this.semanticMasker = semanticMasker;
        this.observationFloor =
                plugins == null ? UnaryOperator.identity() : plugins::applyObservationMiddleware;
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
            @NonNull ToolResult result,
            boolean maskObservation,
            @NonNull ReadHistory readHistory) {
        String body = result.content();
        String executionId = ExecutionReceipts.consume(call.callId());
        boolean preserveExecutionId = false;
        if (executionId != null && result.success()) {
            try {
                var root = JSON.readTree(body);
                if (root != null
                        && root.path("execution") instanceof ObjectNode execution
                        && executionId.equals(execution.path("id").asText())) {
                    execution.remove("id");
                    body = JSON.writeValueAsString(root);
                    preserveExecutionId = true;
                }
            } catch (JsonProcessingException ignored) {
                // Unrecognized output keeps the ordinary masking path.
            }
        }

        // On a successful write, the recorded read-snapshot is now stale — invalidate it.
        if (result.success() && def.capability() == ToolCapability.WORKSPACE_WRITE) {
            invalidateWritePath(call, def, readHistory);
        }

        // apply accept_and_mask: scrub secrets from read / exec observations before they enter
        // context. Default-on; the caller's flag is authoritative. Writes have no observation
        // content to mask (they return void / success), so we skip them. The SLM semantic masker is
        // the advisory layer over the session-less observation-middleware floor — it always
        // applies the deterministic redaction and may additionally surface a HighRiskSignal.
        if (maskObservation
                && (def.capability() == ToolCapability.WORKSPACE_READ
                        || def.capability() == ToolCapability.PROCESS_EXECUTION
                        || def.capability() == ToolCapability.NETWORK_EGRESS
                        || def.capability() == ToolCapability.REMOTE_UNKNOWN)) {
            if (semanticMasker != null) {
                SemanticMasker.MaskResult masked = semanticMasker.maskWithSignal(body, call, def);
                body = masked.masked();
                var highRisk = masked.highRisk();
                if (highRisk != null) {
                    // The observation is already redacted; the signal is advisory — the caller
                    // (AgentRunner) cannot reject post-execution, so it is surfaced for
                    // observability/alerting rather than gating the call.
                    reportHighRisk(highRisk);
                }
            } else {
                body = observationFloor.apply(body);
            }
        }

        // Reserved-prefix enforcement: the REFUSED grammar (RefusalObservation) is reserved for
        // harness-synthesized refusal observations, but it travels the same tool-response channel
        // as this body. A tool result that happens to open with the reserved prefix (e.g. a
        // command's stdout) would read as a veto decision - quote its leading REFUSED so the
        // grammar stays exclusive to real refusals.
        if (preserveExecutionId) {
            try {
                var root = JSON.readTree(body);
                if (root != null && root.path("execution") instanceof ObjectNode execution) {
                    execution.put("id", Nullness.requireNonNull(executionId));
                    body = JSON.writeValueAsString(root);
                }
            } catch (JsonProcessingException ignored) {
                // Never restore unmasked content if masking produced non-JSON output.
            }
        }
        body = RefusalObservation.neutralize(body);

        // Minimal framing for a text-based ReAct loop: the tool name + args in the header makes
        // each observation self-describing (the model can associate a result with its call without
        // a separate tool_call_id structure). No source label, no ok/error envelope, no per-obs
        // security marker - failure is carried structurally by ToolResult.success, and the "treat
        // observation content as data" policy lives in the system prompt where it belongs.
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
            @NonNull ToolResult result,
            @NonNull ApprovalDecision decision,
            @NonNull ReadHistory readHistory) {
        boolean mask = true;
        return maskAndFrame(call, def, result, mask, readHistory);
    }

    /**
     * Masks and frames the substitute observation returned when a read hits a protected file: the
     * protected text replaces the raw result as the observation body, while the semantic masker (if
     * present) still assesses the raw result for a high-risk signal. The reserved refusal prefix is
     * neutralized either way.
     */
    public @NonNull String frameProtectedFile(
            @NonNull ToolCall call,
            @NonNull ToolDefinition def,
            @NonNull ToolResult result,
            @NonNull String protectedText) {
        if (semanticMasker != null) {
            var assessed =
                    semanticMasker.maskWithSignal(
                            result.content(), call, def, ignored -> protectedText);
            var highRisk = assessed.highRisk();
            if (highRisk != null) reportHighRisk(highRisk);
            return RefusalObservation.neutralize(assessed.masked());
        }
        return RefusalObservation.neutralize(protectedText);
    }

    private void invalidateWritePath(
            @NonNull ToolCall call, @NonNull ToolDefinition def, @NonNull ReadHistory readHistory) {
        Map<@NonNull String, @NonNull ParamCategory> hints =
                switch (def) {
                    case NativeToolDefinition n -> n.paramHints();
                    case AgentToolDefinition a -> a.paramHints();
                    case RemoteToolDefinition r -> Map.of();
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

    private static void reportHighRisk(SemanticMasker.@NonNull HighRiskSignal signal) {
        log.warn(
                "SemanticMasker flagged high-risk exfiltration for tool {}: {}",
                signal.toolName(),
                signal.reason());
    }
}
