package top.focess.veto.agent.screening;

import static org.junit.jupiter.api.Assertions.*;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

class ScreeningModeTest {

    @Test
    void strictModeMatrix() {
        ScreeningMode m = ScreeningMode.STRICT;
        assertEquals(ScreeningOutcome.APPROVE, m.cell(Relevance.HIGH, Danger.SAFE));
        assertEquals(ScreeningOutcome.ASK, m.cell(Relevance.HIGH, Danger.ELEVATED));
        assertEquals(ScreeningOutcome.ASK, m.cell(Relevance.HIGH, Danger.DANGEROUS));
        assertEquals(ScreeningOutcome.REFUSED, m.cell(Relevance.HIGH, Danger.CRITICAL));
        assertEquals(ScreeningOutcome.ASK, m.cell(Relevance.MEDIUM, Danger.SAFE));
        assertEquals(ScreeningOutcome.ASK, m.cell(Relevance.LOW, Danger.SAFE));
        assertEquals(ScreeningOutcome.REFUSED, m.cell(Relevance.LOW, Danger.CRITICAL));
    }

    @Test
    void balancedModeMatrix() {
        ScreeningMode m = ScreeningMode.BALANCED;
        assertEquals(ScreeningOutcome.APPROVE, m.cell(Relevance.HIGH, Danger.SAFE));
        assertEquals(ScreeningOutcome.APPROVE, m.cell(Relevance.HIGH, Danger.ELEVATED));
        assertEquals(ScreeningOutcome.ASK, m.cell(Relevance.HIGH, Danger.DANGEROUS));
        assertEquals(ScreeningOutcome.REFUSED, m.cell(Relevance.HIGH, Danger.CRITICAL));
        assertEquals(ScreeningOutcome.APPROVE, m.cell(Relevance.MEDIUM, Danger.SAFE));
        assertEquals(ScreeningOutcome.ASK, m.cell(Relevance.MEDIUM, Danger.ELEVATED));
    }

    @Test
    void balancedLowSafeIsAsk() {
        // BALANCED row: LOW -> ASK, ASK, ASK, REFUSED. SAFE must NOT auto-approve at LOW
        // relevance (previously the matrix approved SAFE for any relevance — regression guard).
        ScreeningMode m = ScreeningMode.BALANCED;
        assertEquals(ScreeningOutcome.ASK, m.cell(Relevance.LOW, Danger.SAFE));
        assertEquals(ScreeningOutcome.ASK, m.cell(Relevance.LOW, Danger.ELEVATED));
        assertEquals(ScreeningOutcome.ASK, m.cell(Relevance.LOW, Danger.DANGEROUS));
        assertEquals(ScreeningOutcome.REFUSED, m.cell(Relevance.LOW, Danger.CRITICAL));
    }

    @Test
    void permissiveStillRequiresAuthorizationForDangerousCalls() {
        ScreeningMode m = ScreeningMode.PERMISSIVE;
        assertEquals(ScreeningOutcome.APPROVE, m.cell(Relevance.HIGH, Danger.SAFE));
        assertEquals(ScreeningOutcome.APPROVE, m.cell(Relevance.HIGH, Danger.ELEVATED));
        assertEquals(ScreeningOutcome.ASK, m.cell(Relevance.HIGH, Danger.DANGEROUS));
        assertEquals(ScreeningOutcome.REFUSED, m.cell(Relevance.HIGH, Danger.CRITICAL));
        assertEquals(ScreeningOutcome.ASK, m.cell(Relevance.LOW, Danger.DANGEROUS));
    }

    @Test
    void criticalIsRefusedInEveryMode() {
        for (ScreeningMode m : modes()) {
            for (Relevance r : relevances()) {
                assertEquals(
                        ScreeningOutcome.REFUSED,
                        m.cell(r, Danger.CRITICAL),
                        m + "/" + r + "/CRITICAL must be REFUSED");
            }
        }
    }

    private static @NonNull ScreeningMode @NonNull [] modes() {
        ScreeningMode[] values = ScreeningMode.values();
        if (values == null) throw new AssertionError("ScreeningMode.values returned null");
        return values;
    }

    private static @NonNull Relevance @NonNull [] relevances() {
        Relevance[] values = Relevance.values();
        if (values == null) throw new AssertionError("Relevance.values returned null");
        return values;
    }

    @Test
    void lowDangerousAlwaysRequiresAuthorization() {
        // (LOW, DANGEROUS) is the injection signature and must never auto-approve.
        assertEquals(
                ScreeningOutcome.ASK, ScreeningMode.STRICT.cell(Relevance.LOW, Danger.DANGEROUS));
        assertEquals(
                ScreeningOutcome.ASK, ScreeningMode.BALANCED.cell(Relevance.LOW, Danger.DANGEROUS));
        assertEquals(
                ScreeningOutcome.ASK,
                ScreeningMode.PERMISSIVE.cell(Relevance.LOW, Danger.DANGEROUS));
    }
}
