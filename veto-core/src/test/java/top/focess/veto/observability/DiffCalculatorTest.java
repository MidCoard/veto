package top.focess.veto.observability;

import static org.junit.jupiter.api.Assertions.*;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("initialization.field.uninitialized")
class DiffCalculatorTest {

    private @NonNull DiffCalculator diffCalculator;

    @BeforeEach
    void setUp() {
        diffCalculator = new DiffCalculator();
    }

    @Test
    void testIdenticalStrings() {
        @NonNull String text = "line1\nline2\nline3";
        DiffCalculator.@NonNull DiffResult result = diffCalculator.computeDiff(text, text);
        assertEquals(0, result.totalChanges());
        assertFalse(result.hasChanges());
    }

    @Test
    void testDifferentStrings() {
        @NonNull String original = "line1\nline2\nline3";
        @NonNull String redacted = "line1\nline2 [REDACTED]\nline3";

        DiffCalculator.@NonNull DiffResult result = diffCalculator.computeDiff(original, redacted);
        assertTrue(result.hasChanges());
        assertTrue(result.totalChanges() >= 1);
    }

    @Test
    void testLineCountDifference() {
        @NonNull String original = "a\nb\nc";
        @NonNull String redacted = "a\nx\ny\nz";

        DiffCalculator.@NonNull DiffResult result = diffCalculator.computeDiff(original, redacted);
        assertTrue(result.hasChanges());
        assertEquals(1, result.lineCountChange()); // 4 lines - 3 lines = +1
        assertTrue(result.totalChanges() >= 3); // lines 2,3,4 changed
    }

    @Test
    void testSummaryReport() {
        @NonNull String original = "line1\nline2\nline3";
        @NonNull String redacted = "line1\nCHANGED\nline3";

        DiffCalculator.@NonNull DiffResult result = diffCalculator.computeDiff(original, redacted);
        @NonNull String report = diffCalculator.generateSummaryReport(result);
        assertNotNull(report);
        assertTrue(report.contains("Veto Redaction Diff Report"));
        assertTrue(report.contains("L2"));
    }
}
