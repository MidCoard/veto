package top.focess.veto.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration for observability Observability & Shadow Audit. */
@Configuration
@ConfigurationProperties(prefix = "veto.observability")
public class ObservabilityConfiguration {

    private String auditLogPath = "./audit/";
    private int logRotationDays = 365;
    private boolean encryptionEnabled = true;
    private String encryptionKey = "default-veto-audit-key-change-me";
    private boolean tamperProof = true;

    public String getAuditLogPath() {
        return auditLogPath;
    }

    public void setAuditLogPath(String auditLogPath) {
        this.auditLogPath = auditLogPath;
    }

    public int getLogRotationDays() {
        return logRotationDays;
    }

    public void setLogRotationDays(int logRotationDays) {
        this.logRotationDays = logRotationDays;
    }

    public boolean isEncryptionEnabled() {
        return encryptionEnabled;
    }

    public void setEncryptionEnabled(boolean encryptionEnabled) {
        this.encryptionEnabled = encryptionEnabled;
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public boolean isTamperProof() {
        return tamperProof;
    }

    public void setTamperProof(boolean tamperProof) {
        this.tamperProof = tamperProof;
    }
}
