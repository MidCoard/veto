package top.focess.veto.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.model.AuditRecord;

class TamperProofStoreTest {

    @Test
    void encryptedExportDecryptsEachFramedRecord(@TempDir @NonNull Path tempDir) {
        var configuration = new ObservabilityConfiguration();
        configuration.setAuditLogPath(tempDir.toString());
        configuration.setEncryptionEnabled(true);
        configuration.setEncryptionKey("isolated-test-encryption-key");
        var store = new TamperProofStore(configuration);
        store.initialize();
        var first =
                store.append(
                        "dag-1",
                        "request-1",
                        "test",
                        "before",
                        "after",
                        "first",
                        AuditRecord.AuditAction.TOOL_EXECUTION,
                        false);
        var second =
                store.append(
                        "dag-2",
                        "request-2",
                        "test",
                        "before",
                        "after",
                        "second",
                        AuditRecord.AuditAction.TOOL_EXECUTION,
                        false);
        var records =
                store.exportRecords(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        assertEquals(2, records.size());
        assertTrue(records.get(0).contains(first.getId()));
        assertTrue(records.get(1).contains(second.getId()));
    }

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
