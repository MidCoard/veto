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

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.observability.AuditLogger");

    private final @NonNull ObservabilityConfiguration config;
    private final @NonNull TamperProofStore tamperProofStore;

    private final @NonNull AtomicLong totalRecordsWritten = new AtomicLong(0);

    public AuditLogger(
            @NonNull ObservabilityConfiguration config,
            @NonNull TamperProofStore tamperProofStore) {
        this.config = config;
        this.tamperProofStore = tamperProofStore;
    }

    @PostConstruct
    public void init() {
        tamperProofStore.initialize();
        log.info(
                "observability AuditLogger: Initialized. hashChain=true, encryption={}",
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
            boolean vetoApplied) {

        AuditRecord record =
                tamperProofStore.append(
                        dagPayloadId,
                        requestId,
                        componentSource,
                        originalPayload,
                        redactedPayload,
                        diffExcerpt,
                        AuditRecord.AuditAction.VETO_INTERCEPTION,
                        vetoApplied);
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
        tamperProofStore.append(
                dagPayloadId,
                requestId,
                componentSource,
                requestPayload,
                resultPayload,
                "(tool execution)",
                AuditRecord.AuditAction.TOOL_EXECUTION,
                false);
        totalRecordsWritten.incrementAndGet();
    }

    /** Log an LLM exchange event (request/response). */
    public void logLLMExchange(
            @NonNull String requestId,
            @NonNull String modelName,
            @NonNull String requestPayload,
            @NonNull String rawResponsePayload) {
        tamperProofStore.append(
                "LLM-EXCHANGE",
                requestId,
                "gateway",
                requestPayload,
                rawResponsePayload,
                "Model: " + modelName,
                AuditRecord.AuditAction.LLM_EXCHANGE,
                false);

        totalRecordsWritten.incrementAndGet();
    }

    /** Log a credential injection event (without the actual credential values). */
    public void logCredentialInjection(
            @NonNull String dagPayloadId, @NonNull String requestId, @NonNull String credKeys) {
        tamperProofStore.append(
                dagPayloadId,
                requestId,
                "vault",
                credKeys,
                "(injected)",
                "(credential injection - values redacted from audit)",
                AuditRecord.AuditAction.CREDENTIAL_INJECTION,
                false);
        totalRecordsWritten.incrementAndGet();
    }

    /** Log a system error event. */
    public void logError(
            @NonNull String dagPayloadId,
            @NonNull String requestId,
            @NonNull String componentSource,
            @NonNull String errorMessage) {
        tamperProofStore.append(
                dagPayloadId,
                requestId,
                componentSource,
                errorMessage,
                "(error)",
                "(error event)",
                AuditRecord.AuditAction.SYSTEM_ERROR,
                true);
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
