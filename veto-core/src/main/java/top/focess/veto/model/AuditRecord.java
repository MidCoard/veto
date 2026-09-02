package top.focess.veto.model;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * A tamper-proof audit record capturing pre/post redaction diffs for observability Observability.
 * Each record is self-validating via a SHA-256 hash chain.
 */
public class AuditRecord {

    private final @NonNull String id;
    private final @NonNull String dagPayloadId;
    private final @NonNull String requestId;
    private final @NonNull String componentSource;
    private final @NonNull String rawPayloadHash;
    private final @NonNull String redactedPayloadHash;
    private final @NonNull String diffExcerpt;
    private final @NonNull String previousRecordHash;
    private final @NonNull String currentHash;
    private final @NonNull Instant timestamp;
    private final @NonNull AuditAction action;
    private final boolean vetoApplied;

    @SuppressWarnings("method.invocation")
    public AuditRecord(
            @NonNull String dagPayloadId,
            @NonNull String requestId,
            @NonNull String componentSource,
            @NonNull String rawPayload,
            @NonNull String redactedPayload,
            @NonNull String diffExcerpt,
            @NonNull String previousRecordHash,
            @NonNull AuditAction action,
            boolean vetoApplied) {
        this.id = UUID.randomUUID().toString();
        this.dagPayloadId = dagPayloadId;
        this.requestId = requestId;
        this.componentSource = componentSource;
        this.rawPayloadHash = sha256(rawPayload);
        this.redactedPayloadHash = sha256(redactedPayload);
        this.diffExcerpt = diffExcerpt;
        this.previousRecordHash = previousRecordHash;
        this.timestamp = Instant.now();
        this.action = action;
        this.vetoApplied = vetoApplied;
        this.currentHash = computeHash();
    }

    private @NonNull String computeHash() {
        String content =
                id
                        + dagPayloadId
                        + requestId
                        + rawPayloadHash
                        + redactedPayloadHash
                        + diffExcerpt
                        + previousRecordHash
                        + timestamp.toEpochMilli()
                        + action.name()
                        + vetoApplied;
        return sha256(content);
    }

    private static @NonNull String sha256(@NonNull String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public boolean verifyIntegrity(@NonNull String actualPreviousHash) {
        String recomputed = computeHash();
        return currentHash.equals(recomputed) && previousRecordHash.equals(actualPreviousHash);
    }

    // Getters
    public @NonNull String getId() {
        return id;
    }

    public @NonNull String getDagPayloadId() {
        return dagPayloadId;
    }

    public @NonNull String getRequestId() {
        return requestId;
    }

    public @NonNull String getComponentSource() {
        return componentSource;
    }

    public @NonNull String getRawPayloadHash() {
        return rawPayloadHash;
    }

    public @NonNull String getRedactedPayloadHash() {
        return redactedPayloadHash;
    }

    public @NonNull String getDiffExcerpt() {
        return diffExcerpt;
    }

    public @NonNull String getPreviousRecordHash() {
        return previousRecordHash;
    }

    public @NonNull String getCurrentHash() {
        return currentHash;
    }

    public @NonNull Instant getTimestamp() {
        return timestamp;
    }

    public @NonNull AuditAction getAction() {
        return action;
    }

    public boolean isVetoApplied() {
        return vetoApplied;
    }

    public enum AuditAction {
        TOOL_EXECUTION,
        VETO_INTERCEPTION,
        LLM_EXCHANGE,
        CREDENTIAL_INJECTION,
        MCP_DISCOVERY,
        HEARTBEAT,
        SYSTEM_ERROR
    }

    @Override
    public @NonNull String toString() {
        return "AuditRecord{id='"
                + id
                + "', action="
                + action
                + ", vetoApplied="
                + vetoApplied
                + ", ts="
                + timestamp
                + "}";
    }
}
