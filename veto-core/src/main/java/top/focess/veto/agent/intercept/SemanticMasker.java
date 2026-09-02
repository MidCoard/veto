package top.focess.veto.agent.intercept;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.veto.LlamaCppBridge;

/**
 * Advisory semantic masker. Asks the local SLM (via {@link LlamaCppBridge}) whether the agent's
 * emitted call is likely to exfiltrate a secret. This semantic check complements {@link
 * SecretMasker}, the deterministic pattern-based scrubber.
 *
 * <p>The SLM returns a structured JSON verdict: {@code {"risk": "high|medium|low", "reason":
 * "..."}}. On any verdict (high, medium, low) the masker applies {@link SecretMasker} as the
 * redaction — a prior version returned the <b>original</b> observation on "high" risk, which
 * defeated the masking purpose entirely (an exfil attempt that the SLM correctly flagged as
 * high-risk was passed through unmasked to the agent's context). The high-risk signal is now
 * surfaced via {@link HighRiskSignal} so the caller can still reject the call independently of the
 * redaction.
 *
 * <p>When the SLM is unavailable, the masker falls back to {@link SecretMasker} — the deterministic
 * floor is the authoritative layer.
 */
@Component
public class SemanticMasker {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.intercept.SemanticMasker");

    /**
     * Maximum time to wait for the local SLM to score a tool observation. A wedged bridge (native
     * crash, deadlock) must not stall the calling virtual thread indefinitely — fall back to
     * deterministic masking on timeout so the agent's tool cycle keeps moving.
     */
    static final long SLM_TIMEOUT_MS = 2_000L;

    private static final @NonNull Pattern RISK_PATTERN =
            Pattern.compile("\"risk\"\\s*:\\s*\"(high|medium|low)\"");
    private static final @NonNull Pattern EXFIL_PATTERN =
            Pattern.compile(
                    "(?i)\\b(secret|token|password|api[_-]?key|credential|private[_-]?key)\\b");

    private final LlamaCppBridge bridge;

    public SemanticMasker() {
        this(null);
    }

    @Autowired
    public SemanticMasker(@Autowired(required = false) LlamaCppBridge bridge) {
        this.bridge = bridge;
    }

    /** A signal returned alongside the masked observation when the SLM rated the call high-risk. */
    public record HighRiskSignal(@NonNull String toolName, @NonNull String reason) {}

    /**
     * Apply semantic masking: ask the SLM whether the call is a likely exfiltration vector. Returns
     * the (always) SecretMasker-scrubbed observation. A non-null {@link HighRiskSignal} on the
     * side-channel means the SLM said "high" — the caller can still reject the call independently
     * of the redaction.
     */
    public @NonNull MaskResult maskWithSignal(
            @NonNull String observation, @NonNull ToolCall call, @NonNull ToolDefinition def) {
        if (observation.isBlank()) {
            return new MaskResult(observation, null);
        }
        // 1. Fast path: no obvious secret keywords → skip the SLM call.
        if (!EXFIL_PATTERN.matcher(observation).find()) {
            return new MaskResult(SecretMasker.mask(observation), null);
        }
        // 2. SLM unavailable → fall back to deterministic masking.
        if (bridge == null || !bridge.isAvailable()) {
            return new MaskResult(SecretMasker.mask(observation), null);
        }
        try {
            String prompt = buildPrompt(observation, call, def);
            // Bound the wait: a wedged bridge must not stall the calling virtual thread.
            // On timeout / interrupt / execution failure, fall back to deterministic masking
            // (the deterministic floor is authoritative per the Trust Model).
            String response =
                    bridge.infer(prompt, "veto-semantic-mask")
                            .get(SLM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Matcher m = RISK_PATTERN.matcher(response);
            if (!m.find()) {
                return new MaskResult(SecretMasker.mask(observation), null);
            }
            String riskGroup = m.group(1);
            if (riskGroup == null) {
                return new MaskResult(SecretMasker.mask(observation), null);
            }
            String risk = riskGroup.toLowerCase();
            if ("high".equals(risk)) {
                log.warn(
                        "SemanticMasker: SLM rated exfiltration risk HIGH for call {} — redaction"
                                + " applied; caller should reject",
                        call.toolName());
                // Redact the observation AND surface the signal. The previous version returned
                // the original unmasked text here, leaking the secret to the agent's context.
                return new MaskResult(
                        SecretMasker.mask(observation),
                        new HighRiskSignal(call.toolName(), "slm-rated-high"));
            }
            return new MaskResult(SecretMasker.mask(observation), null);
        } catch (TimeoutException e) {
            log.warn(
                    "SemanticMasker: SLM exceeded {}ms — falling back to deterministic masking",
                    SLM_TIMEOUT_MS);
            return new MaskResult(SecretMasker.mask(observation), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn(
                    "SemanticMasker: SLM call interrupted — falling back to deterministic masking");
            return new MaskResult(SecretMasker.mask(observation), null);
        } catch (ExecutionException e) {
            log.debug(
                    "SemanticMasker: SLM inference failed ({}), falling back",
                    safe(e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
            return new MaskResult(SecretMasker.mask(observation), null);
        } catch (Exception e) {
            log.debug(
                    "SemanticMasker: SLM inference failed, falling back: {}", safe(e.getMessage()));
            return new MaskResult(SecretMasker.mask(observation), null);
        }
    }

    /**
     * Backward-compatible single-return API. The redaction is applied uniformly; the high-risk
     * signal is logged and discarded. Prefer {@link #maskWithSignal} so the caller can act on the
     * signal.
     */
    public @NonNull String mask(
            @NonNull String observation, @NonNull ToolCall call, @NonNull ToolDefinition def) {
        MaskResult r = maskWithSignal(observation, call, def);
        return r.masked();
    }

    /** The masker's output: a redacted observation plus an optional high-risk signal. */
    public record MaskResult(@NonNull String masked, HighRiskSignal highRisk) {}

    private static @NonNull String buildPrompt(
            @NonNull String observation, @NonNull ToolCall call, @NonNull ToolDefinition def) {
        String truncated =
                observation.length() > 1000 ? observation.substring(0, 1000) + "..." : observation;
        return "The agent just called "
                + call.toolName()
                + " on a tool with risk="
                + def.risk()
                + ".\nTool call args: "
                + safe(call.args())
                + "\nTool result (truncated to 1000 chars): "
                + truncated
                + "\n\nIs this tool call likely to exfiltrate a secret (API key, password, token, "
                + "private key, credential) to an unintended destination? Reply with a single JSON "
                + "object: {\"risk\": \"high|medium|low\", \"reason\": \"...\"}";
    }

    private static @NonNull String safe(Object o) {
        if (o == null) {
            return "";
        }
        String s = o.toString();
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
