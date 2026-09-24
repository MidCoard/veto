package top.focess.veto.veto;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.observability.AuditLogger;

/**
 * gateway Local SLM Veto Gateway - THE CORE OF PROJECT VETO.
 *
 * <p>The absolute choke point for all outbound data. Intercepts all raw data read by mcp (MCP) or
 * sandbox (Sandbox). 1. Extracts structural schemas 2. Masks sensitive literals through the
 * plugin-owned observation-middleware floor (redaction rules live in the secret-protection plugin,
 * not here) 3. Enforces structural constraints before allowing data to flow to bus (Communication
 * Bus)
 *
 * <p>Uses llama.cpp (quantized 1B-3B) with GBNF grammar-constrained decoding.
 */
@Service
public class VetoGateway {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.veto.VetoGateway");

    private final @NonNull VetoGatewayConfiguration config;
    private final @NonNull LlamaCppBridge llamaCppBridge;
    private final @Nullable PluginManager plugins;
    private final @NonNull AuditLogger auditLogger;

    private final @NonNull AtomicLong totalVetoes = new AtomicLong(0);
    private final @NonNull AtomicLong totalPasses = new AtomicLong(0);
    private final @NonNull AtomicLong totalRedactions = new AtomicLong(0);

    @org.springframework.beans.factory.annotation.Autowired
    public VetoGateway(
            @NonNull VetoGatewayConfiguration config,
            @NonNull LlamaCppBridge llamaCppBridge,
            @NonNull ObjectProvider<PluginManager> plugins,
            @NonNull AuditLogger auditLogger) {
        this(config, llamaCppBridge, plugins.getIfAvailable(), auditLogger);
    }

    VetoGateway(
            @NonNull VetoGatewayConfiguration config,
            @NonNull LlamaCppBridge llamaCppBridge,
            @Nullable PluginManager plugins,
            @NonNull AuditLogger auditLogger) {
        this.config = config;
        this.llamaCppBridge = llamaCppBridge;
        this.plugins = plugins;
        this.auditLogger = auditLogger;
    }

    @PostConstruct
    public void init() {
        if (!config.isEnabled()) {
            log.warn(
                    "gateway VetoGateway: DISABLED by configuration. ALL data will pass through unchecked!");
            return;
        }

        if (!llamaCppBridge.isAvailable()) {
            log.warn(
                    "gateway VetoGateway: SLM not available. Structural block analysis is skipped;"
                            + " the masking floor is unaffected.");
        }

        log.info(
                "gateway VetoGateway: Initialized. masking=plugin-observation-middleware,"
                        + " enforceConstraints={}",
                config.isEnforceStructuralConstraints());
    }

    /**
     * THE VETO GATE - every outbound payload passes through here.
     *
     * @param payload The raw payload data to be sent to the cloud
     * @param dagPayloadId The DAG payload ID for audit trail
     * @param requestId The tool execution request ID for audit trail
     * @param componentSource Source component (mcp MCP or sandbox Sandbox)
     * @return VetoResult containing the decision and processed payload
     */
    public @NonNull VetoResult processOutbound(
            @NonNull String payload,
            @NonNull String dagPayloadId,
            @NonNull String requestId,
            @NonNull String componentSource) {
        if (!config.isEnabled()) {
            return VetoResult.pass(payload, "Veto gateway disabled");
        }

        long startTime = System.currentTimeMillis();
        log.info(
                "gateway VetoGateway: Processing outbound payload ({} bytes, source={})",
                payload.length(),
                componentSource);

        try {
            // Step 1-2: sensitive-data masking through the plugin observation-middleware chain.
            // The plugins own the redaction rules; without a plugin the payload passes unmasked.
            var manager = plugins;
            String masked = manager == null ? payload : manager.applyObservationMiddleware(payload);

            // SLM structural-compliance analysis; only the block decision signal is consumed.
            String slmAnalysis = "";
            if (llamaCppBridge.isAvailable()) {
                try {
                    String analysisPrompt =
                            PromptCompiler.compileText(
                                    "outbound-analysis", Map.of("payload", masked));
                    slmAnalysis = llamaCppBridge.infer(analysisPrompt, "veto-output").join();
                } catch (Exception e) {
                    log.error("Local SLM failed (OOM/error); skipping the block analysis.", e);
                }
            }

            // Step 3: Structural constraint enforcement
            String finalPayload = enforceStructuralConstraints(masked);

            // Step 4: Determine veto decision
            VetoDecision decision;
            String reason;
            boolean wasRedacted = !payload.equals(finalPayload);
            int changedLines = countChangedLines(payload, finalPayload);

            if (wasRedacted || slmAnalysis.contains("\"veto_decision\":\"block\"")) {
                decision = VetoDecision.REDACT;
                totalVetoes.incrementAndGet();
                reason =
                        "Payload required masking or structural enforcement ("
                                + changedLines
                                + " changed lines, SLM analysis)";
                log.info("gateway VetoGateway: VETO/REDACT applied  - {}", reason);
            } else {
                decision = VetoDecision.PASS;
                totalPasses.incrementAndGet();
                reason = "Payload passed all checks";
            }

            // Step 5: Log to audit trail (observability)
            String diff = computeDiff(payload, finalPayload);
            auditLogger.logRedaction(
                    dagPayloadId,
                    requestId,
                    componentSource,
                    payload,
                    finalPayload,
                    diff,
                    decision == VetoDecision.REDACT);

            if (wasRedacted) {
                totalRedactions.addAndGet(changedLines);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info(
                    "gateway VetoGateway: Decision={}, elapsed={}ms, changedLines={}",
                    decision,
                    elapsed,
                    changedLines);

            return new VetoResult(decision, finalPayload, reason, changedLines);

        } catch (Exception e) {
            log.error("gateway VetoGateway: Processing error  - falling back to BLOCK", e);
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            auditLogger.logError(dagPayloadId, requestId, componentSource, detail);
            return VetoResult.block("Veto gateway processing error: " + e.getMessage());
        }
    }

    /**
     * Enforce structural constraints on the payload. Validates that the data adheres to project
     * rules (e.g., normalized physics values).
     */
    private @NonNull String enforceStructuralConstraints(@NonNull String payload) {
        if (!config.isEnforceStructuralConstraints()) {
            return payload;
        }

        String result = payload;

        // Example structural enforcement: ensure physics parameters use normalized peak values
        result =
                result.replaceAll(
                        "(?i)MIN_DB\\s*[:=]\\s*[-+]?\\d+(?:\\.\\d+)?",
                        "[ENFORCED_NORMALIZED_PEAK]");

        // Enforce discrete physical solutions over continuous approximations
        result =
                result.replaceAll(
                        "(?i)continuous_approx\\s*[:=]\\s*\\w+",
                        "continuous_approx: [ENFORCED_DISCRETE]");

        return result;
    }

    private static int countChangedLines(@NonNull String original, @NonNull String changed) {
        String[] originalLines = original.split("\n");
        String[] changedLines = changed.split("\n");
        int changes = 0;
        int max = Math.max(originalLines.length, changedLines.length);
        for (int i = 0; i < max; i++) {
            String originalLine = i < originalLines.length ? originalLines[i] : "";
            String changedLine = i < changedLines.length ? changedLines[i] : "";
            if (!originalLine.equals(changedLine)) changes++;
        }
        return changes;
    }

    private @NonNull String computeDiff(@NonNull String original, @NonNull String redacted) {
        if (original.equals(redacted)) return "(no changes)";
        // Simple diff for audit: show changed sections
        StringBuilder diff = new StringBuilder();
        String[] origLines = original.split("\n");
        String[] redactedLines = redacted.split("\n");

        int max = Math.max(origLines.length, redactedLines.length);
        int changes = 0;
        for (int i = 0; i < max && changes < 20; i++) {
            String o = i < origLines.length ? origLines[i] : "";
            String r = i < redactedLines.length ? redactedLines[i] : "";
            if (!o.equals(r)) {
                diff.append("L").append(i + 1).append(": -").append(o).append("\n");
                diff.append("  +").append(r).append("\n");
                changes++;
            }
        }
        if (origLines.length != redactedLines.length) {
            diff.append("(line count changed: ")
                    .append(origLines.length)
                    .append(" -> ")
                    .append(redactedLines.length)
                    .append(")");
        }
        return diff.toString();
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    public long getTotalVetoes() {
        return totalVetoes.get();
    }

    public long getTotalPasses() {
        return totalPasses.get();
    }

    public long getTotalRedactions() {
        return totalRedactions.get();
    }

    /**
     * The result of processing a payload through the Veto Gateway. {@code redactionCount} counts
     * payload lines changed by masking or structural enforcement (0 for a clean pass).
     */
    public record VetoResult(
            @NonNull VetoDecision decision,
            @NonNull String processedPayload,
            @NonNull String reason,
            int redactionCount) {

        public static @NonNull VetoResult pass(@NonNull String payload, @NonNull String reason) {
            return new VetoResult(VetoDecision.PASS, payload, reason, 0);
        }

        public static @NonNull VetoResult block(@NonNull String reason) {
            return new VetoResult(VetoDecision.BLOCK, "", reason, 0);
        }

        public boolean isAllowed() {
            return decision == VetoDecision.PASS || decision == VetoDecision.REDACT;
        }
    }

    public enum VetoDecision {
        PASS, // No redaction needed
        REDACT, // Redactions applied, payload is safe
        BLOCK // Payload blocked entirely
    }
}
