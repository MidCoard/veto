package top.focess.veto.observability;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DiffCalculatorTest {

  private DiffCalculator diffCalculator;

  @BeforeEach
  void setUp() {
    diffCalculator = new DiffCalculator();
  }

  @Test
  void testIdenticalStrings() {
    String text = "line1\nline2\nline3";
    DiffCalculator.DiffResult result = diffCalculator.computeDiff(text, text);
    assertEquals(0, result.totalChanges());
    assertFalse(result.hasChanges());
  }

  @Test
  void testDifferentStrings() {
    String original = "line1\nline2\nline3";
    String redacted = "line1\nline2 [REDACTED]\nline3";

    DiffCalculator.DiffResult result = diffCalculator.computeDiff(original, redacted);
    assertTrue(result.hasChanges());
    assertTrue(result.totalChanges() >= 1);
  }

  @Test
  void testNullInputs() {
    DiffCalculator.DiffResult result = diffCalculator.computeDiff(null, null);
    assertEquals(0, result.totalChanges());
  }

  @Test
  void testLineCountDifference() {
    String original = "a\nb\nc";
    String redacted = "a\nx\ny\nz";

    DiffCalculator.DiffResult result = diffCalculator.computeDiff(original, redacted);
    assertTrue(result.hasChanges());
    assertEquals(1, result.lineCountChange()); // 4 lines - 3 lines = +1
    assertTrue(result.totalChanges() >= 3); // lines 2,3,4 changed
  }

  @Test
  void testSummaryReport() {
    String original = "line1\nline2\nline3";
    String redacted = "line1\nCHANGED\nline3";

    DiffCalculator.DiffResult result = diffCalculator.computeDiff(original, redacted);
    String report = diffCalculator.generateSummaryReport(result);
    assertNotNull(report);
    assertTrue(report.contains("Veto Redaction Diff Report"));
    assertTrue(report.contains("L2"));
  }
}
