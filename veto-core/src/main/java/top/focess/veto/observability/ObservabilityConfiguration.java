package top.focess.veto.observability;

import java.nio.file.Path;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration for observability Observability & Shadow Audit. */
@Configuration
@ConfigurationProperties(prefix = "veto.observability")
public class ObservabilityConfiguration {

    private String auditLogPath;
    private boolean encryptionEnabled = true;
    private @NonNull String encryptionKey = "default-veto-audit-key-change-me";

    /** The configured audit log directory. @throws IllegalStateException if never set */
    public @NonNull String getAuditLogPath() {
        if (auditLogPath == null) {
            throw new IllegalStateException("An explicit audit log path is required");
        }
        return auditLogPath;
    }

    /** Sets the audit log directory. @throws IllegalArgumentException if not an absolute path */
    public void setAuditLogPath(@NonNull String auditLogPath) {
        if (!Path.of(auditLogPath).isAbsolute()) {
            throw new IllegalArgumentException("Audit log path must be absolute");
        }
        this.auditLogPath = auditLogPath;
    }

    public boolean isEncryptionEnabled() {
        return encryptionEnabled;
    }

    public void setEncryptionEnabled(boolean encryptionEnabled) {
        this.encryptionEnabled = encryptionEnabled;
    }

    public @NonNull String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(@NonNull String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }
}
