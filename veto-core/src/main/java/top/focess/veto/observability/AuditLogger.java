package top.focess.veto.observability;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import top.focess.veto.model.AuditRecord;

/**
 * observability Observability & Shadow Audit - top-level service. A tamper-proof black box that
 * logs the exact diff of data before and after the gateway Veto Gateway redaction. Logs are
 * encrypted and stored locally for enterprise compliance audits.
 */
@Service
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    private final @NonNull ObservabilityConfiguration config;
    private final @NonNull TamperProofStore tamperProofStore;
    private final @NonNull DiffCalculator diffCalculator;

    private final @NonNull AtomicLong totalRecordsWritten = new AtomicLong(0);

    public AuditLogger(
            @NonNull ObservabilityConfiguration config,
            @NonNull TamperProofStore tamperProofStore,
            @NonNull DiffCalculator diffCalculator) {
        this.config = config;
        this.tamperProofStore = tamperProofStore;
        this.diffCalculator = diffCalculator;
    }

    @PostConstruct
    public void init() {
        tamperProofStore.initialize();
        log.info(
                "observability AuditLogger: Initialized. tamperProof={}, encryption={}",
                config.isTamperProof(),
                config.isEncryptionEnabled());
    }

    @PreDestroy
    public void shutdown() {
        log.info(
                "observability AuditLogger: Shut down. {} records written this session.",
                totalRecordsWritten.get());
    }

    /** Log a veto redaction event with pre/post data. */
    public void logRedaction(
            @NonNull String dagPayloadId,
            @NonNull String requestId,
            @NonNull String componentSource,
            @NonNull String originalPayload,
            @NonNull String redactedPayload,
            @NonNull String diffExcerpt,
            @NonNull String previousRecordHash,
            boolean vetoApplied) {

        AuditRecord record =
                new AuditRecord(
                        dagPayloadId,
                        requestId,
                        componentSource,
                        originalPayload,
                        redactedPayload,
                        diffExcerpt,
                        previousRecordHash,
                        AuditRecord.AuditAction.VETO_INTERCEPTION,
                        vetoApplied);

        tamperProofStore.append(record);
        totalRecordsWritten.incrementAndGet();

        log.info(
                "observability Audit: Logged veto interception for payload={} (vetoed={}, hash={}...)",
                dagPayloadId,
                vetoApplied,
                record.getCurrentHash()
                        .substring(0, Math.min(8, record.getCurrentHash().length())));
    }

    /** Log a tool execution event. */
    public void logToolExecution(
            @NonNull String dagPayloadId,
            @NonNull String requestId,
            @NonNull String componentSource,
            @NonNull String requestPayload,
            @NonNull String resultPayload) {
        AuditRecord record =
                new AuditRecord(
                        dagPayloadId,
                        requestId,
                        componentSource,
                        requestPayload,
                        resultPayload,
                        "(tool execution)",
                        tamperProofStore.getChainTailHash(),
                        AuditRecord.AuditAction.TOOL_EXECUTION,
                        false);

        tamperProofStore.append(record);
        totalRecordsWritten.incrementAndGet();
    }

    /** Log an LLM exchange event (request/response). */
    public void logLLMExchange(
            @NonNull String requestId,
            @NonNull String modelName,
            @NonNull String requestPayload,
            @NonNull String rawResponsePayload) {
        AuditRecord record =
                new AuditRecord(
                        "LLM-EXCHANGE",
                        requestId,
                        "gateway",
                        requestPayload,
                        rawResponsePayload,
                        "Model: " + modelName,
                        tamperProofStore.getChainTailHash(),
                        AuditRecord.AuditAction.VETO_INTERCEPTION, // Reusing action or add new one?
                        // VETO_INTERCEPTION is for gateway
                        false);

        tamperProofStore.append(record);
        totalRecordsWritten.incrementAndGet();
    }

    /** Log a credential injection event (without the actual credential values). */
    public void logCredentialInjection(
            @NonNull String dagPayloadId, @NonNull String requestId, @NonNull String credKeys) {
        AuditRecord record =
                new AuditRecord(
                        dagPayloadId,
                        requestId,
                        "vault",
                        credKeys,
                        "(injected)",
                        "(credential injection - values redacted from audit)",
                        tamperProofStore.getChainTailHash(),
                        AuditRecord.AuditAction.CREDENTIAL_INJECTION,
                        false);

        tamperProofStore.append(record);
        totalRecordsWritten.incrementAndGet();
    }

    /** Log a system error event. */
    public void logError(
            @NonNull String dagPayloadId,
            @NonNull String requestId,
            @NonNull String componentSource,
            @NonNull String errorMessage) {
        AuditRecord record =
                new AuditRecord(
                        dagPayloadId,
                        requestId,
                        componentSource,
                        errorMessage,
                        "(error)",
                        "(error event)",
                        tamperProofStore.getChainTailHash(),
                        AuditRecord.AuditAction.SYSTEM_ERROR,
                        true);

        tamperProofStore.append(record);
        totalRecordsWritten.incrementAndGet();
    }

    /** Verify the integrity of the entire audit chain. */
    public TamperProofStore.@NonNull ChainVerificationResult verifyAuditChain() {
        return tamperProofStore.verifyChain();
    }

    public long getTotalRecordsWritten() {
        return totalRecordsWritten.get();
    }
}
