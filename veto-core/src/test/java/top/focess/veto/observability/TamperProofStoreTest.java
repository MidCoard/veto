package top.focess.veto.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.model.AuditRecord;

class TamperProofStoreTest {

    @Test
    void appendOwnsTheHashChainTailAtomically(@TempDir @NonNull Path tempDir) {
        ObservabilityConfiguration configuration = new ObservabilityConfiguration();
        configuration.setAuditLogPath(tempDir.toString());
        configuration.setEncryptionEnabled(false);
        TamperProofStore store = new TamperProofStore(configuration);
        store.initialize();

        AuditRecord first =
                store.append(
                        "dag-1",
                        "request-1",
                        "test",
                        "before",
                        "after",
                        "diff",
                        AuditRecord.AuditAction.VETO_INTERCEPTION,
                        true);
        AuditRecord second =
                store.append(
                        "dag-2",
                        "request-2",
                        "test",
                        "before",
                        "after",
                        "diff",
                        AuditRecord.AuditAction.TOOL_EXECUTION,
                        false);

        assertEquals(first.getCurrentHash(), second.getPreviousRecordHash());
        TamperProofStore.ChainVerificationResult result = store.verifyChain();
        assertEquals(2, result.recordsChecked());
        assertTrue(result.chainIntact(), result.summary());
    }
}
