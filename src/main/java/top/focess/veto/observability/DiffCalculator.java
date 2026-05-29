package top.focess.veto.observability;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * C9 Diff Calculator - computes exact diffs between pre- and post-redaction payloads. Used to
 * produce the shadow audit trail for compliance verification.
 */
@Component
public class DiffCalculator {

  private static final Logger log = LoggerFactory.getLogger(DiffCalculator.class);

  private static final int MAX_DIFF_LENGTH = 100_000; // 100 KB max diff excerpt
  private static final int MAX_DIFF_LINES = 1000;

  /**
   * Compute a structured diff between original and redacted payloads.
   *
   * @param original The original payload before veto processing
   * @param redacted The payload after veto redaction
   * @return A DiffResult containing line-level and character-level difference data
   */
  public DiffResult computeDiff(String original, String redacted) {
    if (original == null || redacted == null) {
      return new DiffResult(0, 0, 0, "(null input)", List.of());
    }

    if (original.equals(redacted)) {
      return new DiffResult(0, 0, 0, "(identical)", List.of());
    }

    long startTime = System.nanoTime();

    String[] origLines = original.split("\n", -1);
    String[] redactLines = redacted.split("\n", -1);

    List<DiffLine> lineDiffs = new ArrayList<>();
    int totalChanges = 0;
    int charsChanged = 0;

    int maxLines = Math.min(Math.max(origLines.length, redactLines.length), MAX_DIFF_LINES);

    for (int i = 0; i < maxLines; i++) {
      String o = i < origLines.length ? origLines[i] : "";
      String r = i < redactLines.length ? redactLines[i] : "";

      if (!o.equals(r)) {
        totalChanges++;
        charsChanged += Math.abs(o.length() - r.length());
        lineDiffs.add(new DiffLine(i + 1, o, r));
      }
    }

    // Handle line count differences
    int lineCountChange = (totalChanges > 0) ? redactLines.length - origLines.length : 0;

    String summary =
        String.format(
            "%d lines changed (%d total), %d character difference, line count delta: %+d",
            totalChanges, maxLines, charsChanged, lineCountChange);

    long elapsedNs = System.nanoTime() - startTime;
    log.debug("C9 DiffCalculator: Computed diff in {}us  - {}", elapsedNs / 1000, summary);

    return new DiffResult(totalChanges, charsChanged, lineCountChange, summary, lineDiffs);
  }

  /** Generate a human-readable diff summary. */
  public String generateSummaryReport(DiffResult diff) {
    if (diff.totalChanges == 0) {
      return "No changes detected.";
    }

    StringBuilder sb = new StringBuilder();
    sb.append("=== Veto Redaction Diff Report ===\n");
    sb.append(diff.summary).append("\n\n");

    int shown = 0;
    for (DiffLine line : diff.lineDiffs) {
      if (shown >= 25) {
        sb.append("... and ").append(diff.totalChanges - 25).append(" more changes\n");
        break;
      }
      sb.append(
          String.format(
              "L%d: -%s%n    +%s%n",
              line.lineNumber, truncate(line.original), truncate(line.redacted)));
      shown++;
    }

    return sb.toString();
  }

  private String truncate(String s) {
    if (s == null || s.length() <= 120) return s != null ? s : "";
    return s.substring(0, 120) + "...";
  }

  /** Result of a diff computation. */
  public record DiffResult(
      int totalChanges,
      int charsChanged,
      int lineCountChange,
      String summary,
      List<DiffLine> lineDiffs) {
    public boolean hasChanges() {
      return totalChanges > 0;
    }
  }

  /** A single line-level diff entry. */
  public record DiffLine(int lineNumber, String original, String redacted) {
    public boolean isModified() {
      return !original.equals(redacted);
    }

    public boolean isAdded() {
      return original.isEmpty();
    }

    public boolean isRemoved() {
      return redacted.isEmpty();
    }
  }
}
