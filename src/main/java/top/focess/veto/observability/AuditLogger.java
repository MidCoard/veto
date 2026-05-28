package top.focess.veto.observability;

import top.focess.veto.model.AuditRecord;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * C9 Observability & Shadow Audit â€?top-level service.
 * A tamper-proof black box that logs the exact diff of data before and after
 * the C7 Veto Gateway redaction. Logs are encrypted and stored locally for
 * enterprise compliance audits.
 */
@Service
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    private final ObservabilityConfiguration config;
    private final TamperProofStore tamperProofStore;
    private final DiffCalculator diffCalculator;

    private final AtomicLong totalRecordsWritten = new AtomicLong(0);

    public AuditLogger(ObservabilityConfiguration config,
                       TamperProofStore tamperProofStore,
                       DiffCalculator diffCalculator) {
        this.config = config;
        this.tamperProofStore = tamperProofStore;
        this.diffCalculator = diffCalculator;
    }

    @PostConstruct
    public void init() {
        tamperProofStore.initialize();
        log.info("C9 AuditLogger: Initialized. tamperProof={}, encryption={}",
            config.isTamperProof(), config.isEncryptionEnabled());
    }

    @PreDestroy
    public void shutdown() {
        log.info("C9 AuditLogger: Shut down. {} records written this session.", totalRecordsWritten.get());
    }

    /**
     * Log a veto redaction event with pre/post data.
     */
    public void logRedaction(String dagPayloadId, String requestId, String componentSource,
                             String originalPayload, String redactedPayload, String diffExcerpt,
                             String previousRecordHash, boolean vetoApplied) {

        AuditRecord record = new AuditRecord(
            dagPayloadId, requestId, componentSource,
            originalPayload, redactedPayload, diffExcerpt,
            previousRecordHash,
            AuditRecord.AuditAction.VETO_INTERCEPTION,
            vetoApplied
        );

        tamperProofStore.append(record);
        totalRecordsWritten.incrementAndGet();

        log.info("C9 Audit: Logged veto interception for payload={} (vetoed={}, hash={}...)",
            dagPayloadId, vetoApplied,
            record.getCurrentHash().substring(0, Math.min(8, record.getCurrentHash().length())));
    }

    /**
     * Log a tool execution event.
     */
    public void logToolExecution(String dagPayloadId, String requestId, String componentSource,
                                 String requestPayload, String resultPayload) {
        AuditRecord record = new AuditRecord(
            dagPayloadId, requestId, componentSource,
            requestPayload, resultPayload, "(tool execution)",
            tamperProofStore.getChainTailHash(),
            AuditRecord.AuditAction.TOOL_EXECUTION,
            false
        );

        tamperProofStore.append(record);
        totalRecordsWritten.incrementAndGet();
    }

    /**
     * Log a credential injection event (without the actual credential values).
     */
    public void logCredentialInjection(String dagPayloadId, String requestId, String credKeys) {
        AuditRecord record = new AuditRecord(
            dagPayloadId, requestId, "C8-Vault",
            credKeys, "(injected)", "(credential injection - values redacted from audit)",
            tamperProofStore.getChainTailHash(),
            AuditRecord.AuditAction.CREDENTIAL_INJECTION,
            false
        );

        tamperProofStore.append(record);
        totalRecordsWritten.incrementAndGet();
    }

    /**
     * Log a system error event.
     */
    public void logError(String dagPayloadId, String requestId, String componentSource, String errorMessage) {
        AuditRecord record = new AuditRecord(
            dagPayloadId, requestId, componentSource,
            errorMessage, "(error)", "(error event)",
            tamperProofStore.getChainTailHash(),
            AuditRecord.AuditAction.SYSTEM_ERROR,
            true
        );

        tamperProofStore.append(record);
        totalRecordsWritten.incrementAndGet();
    }

    /**
     * Verify the integrity of the entire audit chain.
     */
    public TamperProofStore.ChainVerificationResult verifyAuditChain() {
        return tamperProofStore.verifyChain();
    }

    public long getTotalRecordsWritten() { return totalRecordsWritten.get(); }
}
