package top.focess.veto.veto;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.focess.veto.observability.AuditLogger;

/**
 * C7 Local SLM Veto Gateway - THE CORE OF PROJECT VETO.
 *
 * <p>The absolute choke point for all outbound data. Intercepts all raw data read by C4 (MCP) or C6
 * (Sandbox). 1. Extracts structural schemas 2. Redacts sensitive literals (secrets, proprietary
 * physics parameters) 3. Enforces structural constraints before allowing data to flow to C3
 * (Communication Bus)
 *
 * <p>Uses llama.cpp (quantized 1B-3B) with GBNF grammar-constrained decoding.
 */
@Service
public class VetoGateway {

    private static final Logger log = LoggerFactory.getLogger(VetoGateway.class);

    private final VetoGatewayConfiguration config;
    private final LlamaCppBridge llamaCppBridge;
    private final SemanticRedactor semanticRedactor;
    private final GBNFGrammarEngine grammarEngine;
    private final AuditLogger auditLogger;

    private final java.util.concurrent.atomic.AtomicLong totalVetoes =
            new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalPasses =
            new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalRedactions =
            new java.util.concurrent.atomic.AtomicLong(0);

    public VetoGateway(
            VetoGatewayConfiguration config,
            LlamaCppBridge llamaCppBridge,
            SemanticRedactor semanticRedactor,
            GBNFGrammarEngine grammarEngine,
            AuditLogger auditLogger) {
        this.config = config;
        this.llamaCppBridge = llamaCppBridge;
        this.semanticRedactor = semanticRedactor;
        this.grammarEngine = grammarEngine;
        this.auditLogger = auditLogger;
    }

    @PostConstruct
    public void init() {
        if (!config.isEnabled()) {
            log.warn(
                    "C7 VetoGateway: DISABLED by configuration. ALL data will pass through unchecked!");
            return;
        }

        // Start llama.cpp SLM
        boolean slmStarted = llamaCppBridge.start();
        if (!slmStarted) {
            log.warn(
                    "C7 VetoGateway: SLM not available. Running in deterministic-only redaction mode.");
        }

        log.info(
                "C7 VetoGateway: Initialized. interceptAllOutbound={}, redactSecrets={}, enforceConstraints={}",
                config.isInterceptAllOutbound(),
                config.isRedactSecrets(),
                config.isEnforceStructuralConstraints());
    }

    @PreDestroy
    public void shutdown() {
        llamaCppBridge.stop();
        log.info(
                "C7 VetoGateway: Shut down. Processed {} vetoes, {} passes, {} redactions",
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
     * @param componentSource Source component (C4 MCP or C6 Sandbox)
     * @return VetoResult containing the decision and processed payload
     */
    public VetoResult processOutbound(
            String payload, String dagPayloadId, String requestId, String componentSource) {
        if (!config.isEnabled()) {
            return VetoResult.pass(payload, "Veto gateway disabled");
        }

        long startTime = System.currentTimeMillis();
        log.info(
                "C7 VetoGateway: Processing outbound payload ({} bytes, source={})",
                payload.length(),
                componentSource);

        try {
            // Step 1: Deterministic redaction (first pass)
            SemanticRedactor.RedactionReport deterministicReport =
                    semanticRedactor.deterministicRedact(payload);

            // Step 2: SLM semantic analysis (for complex structural enforcement)
            String slmAnalysis = "";
            String llmGuidedPayload = payload;

            if (llamaCppBridge.isAvailable()) {
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
            } else {
                llmGuidedPayload = deterministicReport.redactedPayload();
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
                log.info("C7 VetoGateway: VETO/REDACT applied  - {}", reason);
            } else {
                decision = VetoDecision.PASS;
                totalPasses.incrementAndGet();
                reason = "Payload passed all checks";
            }

            // Step 5: Log to audit trail (C9)
            String diff = computeDiff(payload, finalPayload);
            auditLogger.logRedaction(
                    dagPayloadId,
                    requestId,
                    componentSource,
                    payload,
                    finalPayload,
                    diff,
                    previousRecordHash(),
                    decision == VetoDecision.REDACT || decision == VetoDecision.BLOCK);

            if (wasRedacted) {
                totalRedactions.addAndGet(deterministicReport.getTotalRedactions());
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info(
                    "C7 VetoGateway: Decision={}, elapsed={}ms, redactions={}",
                    decision,
                    elapsed,
                    deterministicReport.getTotalRedactions());

            return new VetoResult(
                    decision, finalPayload, reason, deterministicReport.getTotalRedactions());

        } catch (Exception e) {
            log.error("C7 VetoGateway: Processing error  - falling back to BLOCK", e);
            auditLogger.logError(dagPayloadId, requestId, componentSource, e.getMessage());
            return VetoResult.block("Veto gateway processing error: " + e.getMessage());
        }
    }

    /**
     * Enforce structural constraints on the payload. Validates that the data adheres to project
     * rules (e.g., normalized physics values).
     */
    private String enforceStructuralConstraints(String payload) {
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

    private String computeDiff(String original, String redacted) {
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

    private String previousRecordHash() {
        return ""; // Will be chained by AuditLogger
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
     * The result of processing a payload through the Veto Gateway.
     */
    public record VetoResult(
            VetoDecision decision, String processedPayload, String reason, int redactionCount) {

        public static VetoResult pass(String payload, String reason) {
            return new VetoResult(VetoDecision.PASS, payload, reason, 0);
        }

        public static VetoResult block(String reason) {
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
