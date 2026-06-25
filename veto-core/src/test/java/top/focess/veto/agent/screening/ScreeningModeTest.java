package top.focess.veto.agent.screening;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ScreeningModeTest {

    @Test
    void strictModeMatrix() {
        ScreeningMode m = ScreeningMode.STRICT;
        assertEquals(ScreeningOutcome.APPROVE, m.cell(Relevance.HIGH, Danger.SAFE));
        assertEquals(ScreeningOutcome.ASK, m.cell(Relevance.HIGH, Danger.ELEVATED));
        assertEquals(ScreeningOutcome.ASK, m.cell(Relevance.HIGH, Danger.DANGEROUS));
        assertEquals(ScreeningOutcome.MUST_ASK, m.cell(Relevance.HIGH, Danger.CRITICAL));
        assertEquals(ScreeningOutcome.ASK, m.cell(Relevance.MEDIUM, Danger.SAFE));
        assertEquals(ScreeningOutcome.ASK, m.cell(Relevance.LOW, Danger.SAFE));
        assertEquals(ScreeningOutcome.MUST_ASK, m.cell(Relevance.LOW, Danger.CRITICAL));
    }

    @Test
    void balancedModeMatrix() {
        ScreeningMode m = ScreeningMode.BALANCED;
        assertEquals(ScreeningOutcome.APPROVE, m.cell(Relevance.HIGH, Danger.SAFE));
        assertEquals(ScreeningOutcome.APPROVE, m.cell(Relevance.HIGH, Danger.ELEVATED));
        assertEquals(ScreeningOutcome.ASK, m.cell(Relevance.HIGH, Danger.DANGEROUS));
        assertEquals(ScreeningOutcome.MUST_ASK, m.cell(Relevance.HIGH, Danger.CRITICAL));
        assertEquals(ScreeningOutcome.APPROVE, m.cell(Relevance.MEDIUM, Danger.SAFE));
        assertEquals(ScreeningOutcome.ASK, m.cell(Relevance.MEDIUM, Danger.ELEVATED));
    }

    @Test
    void bypassAllApprovesEverythingExceptCritical() {
        ScreeningMode m = ScreeningMode.BYPASS_ALL;
        assertEquals(ScreeningOutcome.APPROVE, m.cell(Relevance.HIGH, Danger.SAFE));
        assertEquals(ScreeningOutcome.APPROVE, m.cell(Relevance.HIGH, Danger.ELEVATED));
        assertEquals(ScreeningOutcome.APPROVE, m.cell(Relevance.HIGH, Danger.DANGEROUS));
        assertEquals(ScreeningOutcome.MUST_ASK, m.cell(Relevance.HIGH, Danger.CRITICAL));
        assertEquals(ScreeningOutcome.APPROVE, m.cell(Relevance.LOW, Danger.DANGEROUS));
    }

    @Test
    void criticalIsMustAskInEveryMode() {
        for (ScreeningMode m : ScreeningMode.values()) {
            for (Relevance r : Relevance.values()) {
                assertEquals(
                        ScreeningOutcome.MUST_ASK,
                        m.cell(r, Danger.CRITICAL),
                        m + "/" + r + "/CRITICAL must be MUST_ASK");
            }
        }
    }

    @Test
    void lowDangerousIsAskInStrictBalancedButApprovedInBypassAll() {
        // (LOW, DANGEROUS) — the injection signature. STRICT/BALANCED → ASK (grant-skippable);
        // BYPASS_ALL → APPROVE (everything except CRITICAL auto-approves). Per §5 table.
        assertEquals(
                ScreeningOutcome.ASK, ScreeningMode.STRICT.cell(Relevance.LOW, Danger.DANGEROUS));
        assertEquals(
                ScreeningOutcome.ASK, ScreeningMode.BALANCED.cell(Relevance.LOW, Danger.DANGEROUS));
        assertEquals(
                ScreeningOutcome.APPROVE,
                ScreeningMode.BYPASS_ALL.cell(Relevance.LOW, Danger.DANGEROUS));
    }
}
