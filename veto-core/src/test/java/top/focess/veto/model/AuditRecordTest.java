package top.focess.veto.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AuditRecordTest {

    @Test
    void testCreateAndVerifyIntegrity() {
        AuditRecord record =
                new AuditRecord(
                        "dag-1",
                        "req-1",
                        "C7-Veto",
                        "raw data with secrets",
                        "redacted data (safe)",
                        "L1: -raw data with secrets\n  +redacted data (safe)",
                        "", // first record
                        AuditRecord.AuditAction.VETO_INTERCEPTION,
                        true);

        assertNotNull(record.getId());
        assertNotNull(record.getCurrentHash());
        assertFalse(record.getCurrentHash().isEmpty());
        assertTrue(record.isVetoApplied());
        assertEquals("C7-Veto", record.getComponentSource());
    }

    @Test
    void testChainedRecords() {
        AuditRecord first =
                new AuditRecord(
                        "dag-1",
                        "req-1",
                        "C6",
                        "req",
                        "res",
                        "diff1",
                        "",
                        AuditRecord.AuditAction.TOOL_EXECUTION,
                        false);

        AuditRecord second =
                new AuditRecord(
                        "dag-1",
                        "req-2",
                        "C7",
                        "raw",
                        "redacted",
                        "diff2",
                        first.getCurrentHash(),
                        AuditRecord.AuditAction.VETO_INTERCEPTION,
                        true);

        assertTrue(second.verifyIntegrity(first.getCurrentHash()));
        assertNotEquals(first.getCurrentHash(), second.getCurrentHash());
        assertEquals(first.getCurrentHash(), second.getPreviousRecordHash());
    }

    @Test
    void testBrokenChainDetection() {
        AuditRecord first =
                new AuditRecord(
                        "d1",
                        "r1",
                        "C9",
                        "a",
                        "b",
                        "diff",
                        "",
                        AuditRecord.AuditAction.SYSTEM_ERROR,
                        false);
        AuditRecord broken =
                new AuditRecord(
                        "d1",
                        "r1",
                        "C9",
                        "a",
                        "b",
                        "diff",
                        "wrong_hash",
                        AuditRecord.AuditAction.SYSTEM_ERROR,
                        false);

        assertFalse(broken.verifyIntegrity(first.getCurrentHash()));
    }

    @Test
    void testDifferentActions() {
        for (AuditRecord.AuditAction action : AuditRecord.AuditAction.values()) {
            AuditRecord record =
                    new AuditRecord("d1", "r1", "C9", "a", "b", "diff", "", action, false);
            assertEquals(action, record.getAction());
        }
    }
}
