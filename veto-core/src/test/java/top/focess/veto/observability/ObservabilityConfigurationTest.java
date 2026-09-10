package top.focess.veto.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObservabilityConfigurationTest {
    @Test
    void requiresAnExplicitAbsoluteDirectory(@TempDir @NonNull Path directory) {
        ObservabilityConfiguration configuration = new ObservabilityConfiguration();
        assertThrows(IllegalStateException.class, configuration::getAuditLogPath);
        assertThrows(IllegalArgumentException.class, () -> configuration.setAuditLogPath("audit"));
        configuration.setAuditLogPath(directory.toString());
        assertEquals(directory.toString(), configuration.getAuditLogPath());
    }
}
