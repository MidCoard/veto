package top.focess.veto.veto;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.focess.veto.observability.AuditLogger;

/**
 * gateway Local SLM Veto Gateway - THE CORE OF PROJECT VETO.
 *
 * <p>The absolute choke point for all outbound data. Intercepts all raw data read by mcp (MCP) or
 * sandbox (Sandbox). 1. Extracts structural schemas 2. Redacts sensitive literals (secrets,
 * proprietary physics parameters) 3. Enforces structural constraints before allowing data to flow
 * to bus (Communication Bus)
 *
 * <p>Uses llama.cpp (quantized 1B-3B) with GBNF grammar-constrained decoding.
 */
@Service
public class VetoGateway {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.veto.VetoGateway");

    private final @NonNull VetoGatewayConfiguration config;
    private final @NonNull LlamaCppBridge llamaCppBridge;
    private final @NonNull SemanticRedactor semanticRedactor;
    private final @NonNull AuditLogger auditLogger;

    private final @NonNull AtomicLong totalVetoes = new AtomicLong(0);
    private final @NonNull AtomicLong totalPasses = new AtomicLong(0);
    private final @NonNull AtomicLong totalRedactions = new AtomicLong(0);

    public VetoGateway(
            @NonNull VetoGatewayConfiguration config,
            @NonNull LlamaCppBridge llamaCppBridge,
            @NonNull SemanticRedactor semanticRedactor,
            @NonNull AuditLogger auditLogger) {
        this.config = config;
        this.llamaCppBridge = llamaCppBridge;
        this.semanticRedactor = semanticRedactor;
        this.auditLogger = auditLogger;
    }

    @PostConstruct
    public void init() {
        if (!config.isEnabled()) {
            log.warn(
                    "gateway VetoGateway: DISABLED by configuration. ALL data will pass through unchecked!");
            return;
        }

        // Start llama.cpp SLM
        boolean slmStarted = llamaCppBridge.start();
        if (!slmStarted) {
            log.warn(
                    "gateway VetoGateway: SLM not available. Running in deterministic-only redaction mode.");
        }

        log.info(
                "gateway VetoGateway: Initialized. deterministicRedaction=true, enforceConstraints={}",
                config.isEnforceStructuralConstraints());
    }

    @PreDestroy
    public void shutdown() {
        llamaCppBridge.stop();
        log.info(
                "gateway VetoGateway: Shut down. Processed {} vetoes, {} passes, {} redactions",
                totalVetoes.get(),
                totalPasses.get(),
                totalRedactions.get());
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
            // Step 1: Deterministic redaction (first pass)
            SemanticRedactor.RedactionReport deterministicReport =
                    semanticRedactor.deterministicRedact(payload);

            // Step 2: SLM semantic analysis (for complex structural enforcement)
            String slmAnalysis = "";
            String llmGuidedPayload = deterministicReport.redactedPayload();

            if (llamaCppBridge.isAvailable()) {
                try {
                    // Ask the SLM to analyze the payload for structural compliance
                    String analysisPrompt =
                            String.format(
                                    "Analyze the following payload for secrets, proprietary data, and structural compliance:\n%s",
                                    payload);
                    slmAnalysis = llamaCppBridge.infer(analysisPrompt, "veto-output").join();

                    // Apply SLM-guided redactions
                    llmGuidedPayload =
                            semanticRedactor.semanticRedact(
                                    deterministicReport.redactedPayload(), slmAnalysis);
                } catch (Exception e) {
                    log.error("Local SLM failed (OOM/error); applying deterministic fallback.", e);
                    llmGuidedPayload = deterministicReport.redactedPayload();
                }
            }

            // Step 3: Structural constraint enforcement
            String finalPayload = enforceStructuralConstraints(llmGuidedPayload);

            // Step 4: Determine veto decision
            VetoDecision decision;
            String reason;
            boolean wasRedacted =
                    deterministicReport.wasModified() || !payload.equals(finalPayload);

            if (wasRedacted || slmAnalysis.contains("\"veto_decision\":\"block\"")) {
                decision = VetoDecision.REDACT;
                totalVetoes.incrementAndGet();
                reason =
                        "Payload required redaction ("
                                + deterministicReport.getTotalRedactions()
                                + " deterministic, SLM analysis)";
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
                totalRedactions.addAndGet(deterministicReport.getTotalRedactions());
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info(
                    "gateway VetoGateway: Decision={}, elapsed={}ms, redactions={}",
                    decision,
                    elapsed,
                    deterministicReport.getTotalRedactions());

            return new VetoResult(
                    decision, finalPayload, reason, deterministicReport.getTotalRedactions());

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

    /** The result of processing a payload through the Veto Gateway. */
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
