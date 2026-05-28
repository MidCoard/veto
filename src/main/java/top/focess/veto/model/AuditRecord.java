package top.focess.veto.model;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * A tamper-proof audit record capturing pre/post redaction diffs for C9 Observability.
 * Each record is self-validating via a SHA-256 hash chain.
 */
public class AuditRecord {

    private final String id;
    private final String dagPayloadId;
    private final String requestId;
    private final String componentSource;
    private final String rawPayloadHash;
    private final String redactedPayloadHash;
    private final String diffExcerpt;
    private final String previousRecordHash;
    private final String currentHash;
    private final Instant timestamp;
    private final AuditAction action;
    private final boolean vetoApplied;

    public AuditRecord(String dagPayloadId, String requestId, String componentSource,
                       String rawPayload, String redactedPayload, String diffExcerpt,
                       String previousRecordHash, AuditAction action, boolean vetoApplied) {
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

    private String computeHash() {
        String content = id + dagPayloadId + requestId + rawPayloadHash
            + redactedPayloadHash + diffExcerpt + previousRecordHash
            + timestamp.toEpochMilli() + action.name() + vetoApplied;
        return sha256(content);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public boolean verifyIntegrity(String actualPreviousHash) {
        String recomputed = computeHash();
        return currentHash.equals(recomputed) && previousRecordHash.equals(actualPreviousHash);
    }

    // Getters
    public String getId() { return id; }
    public String getDagPayloadId() { return dagPayloadId; }
    public String getRequestId() { return requestId; }
    public String getComponentSource() { return componentSource; }
    public String getRawPayloadHash() { return rawPayloadHash; }
    public String getRedactedPayloadHash() { return redactedPayloadHash; }
    public String getDiffExcerpt() { return diffExcerpt; }
    public String getPreviousRecordHash() { return previousRecordHash; }
    public String getCurrentHash() { return currentHash; }
    public Instant getTimestamp() { return timestamp; }
    public AuditAction getAction() { return action; }
    public boolean isVetoApplied() { return vetoApplied; }

    public enum AuditAction {
        TOOL_EXECUTION,
        VETO_INTERCEPTION,
        CREDENTIAL_INJECTION,
        MCP_DISCOVERY,
        HEARTBEAT,
        SYSTEM_ERROR
    }

    @Override
    public String toString() {
        return "AuditRecord{id='" + id + "', action=" + action +
            ", vetoApplied=" + vetoApplied + ", ts=" + timestamp + "}";
    }
}
