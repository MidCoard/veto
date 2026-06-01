package top.focess.veto.sandbox.capabilities;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.model.ToolExecutionRequest;
import top.focess.veto.sandbox.AtomicCapability;

/**
 * Atomic capability: diff_text_files. Produces a unified diff between two text files within the
 * sandbox. Uses Java's built-in diff (no external diff tool required).
 */
@Component
public class DiffTextFilesCapability implements AtomicCapability {

    private static final Logger log = LoggerFactory.getLogger(DiffTextFilesCapability.class);

    private final Path sandboxRoot;

    public DiffTextFilesCapability() {
        this.sandboxRoot =
                Paths.get(System.getProperty("veto.sandbox.tempDir", "./work/sandbox")).normalize();
    }

    @Override
    public String getName() {
        return "diff_text_files";
    }

    @Override
    public void validate(ToolExecutionRequest request) throws SecurityException {
        String fileA = getStringArg(request, "fileA");
        String fileB = getStringArg(request, "fileB");
        if (fileA == null || fileB == null) {
            throw new IllegalArgumentException("Both 'fileA' and 'fileB' arguments are required");
        }
        resolveSafePath(fileA);
        resolveSafePath(fileB);
    }

    @Override
    public String execute(ToolExecutionRequest request) throws SecurityException {
        validate(request);
        String fileA = getStringArg(request, "fileA");
        String fileB = getStringArg(request, "fileB");

        Path resolvedA = resolveSafePath(fileA);
        Path resolvedB = resolveSafePath(fileB);

        log.info("C6 Sandbox: Diff '{}' vs '{}'", resolvedA.getFileName(), resolvedB.getFileName());

        try {
            List<String> linesA = Files.readAllLines(resolvedA);
            List<String> linesB = Files.readAllLines(resolvedB);

            String diff =
                    computeUnifiedDiff(
                            resolvedA.getFileName().toString(),
                            resolvedB.getFileName().toString(),
                            linesA,
                            linesB);

            return String.format(
                    "{\"status\":\"ok\",\"fileA\":\"%s\",\"fileB\":\"%s\",\"diff\":\"%s\"}",
                    escapeJson(resolvedA.getFileName().toString()),
                    escapeJson(resolvedB.getFileName().toString()),
                    escapeJson(diff));
        } catch (IOException e) {
            log.error("C6 Sandbox: Diff failed", e);
            return "{\"status\":\"error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private String computeUnifiedDiff(
            String nameA, String nameB, List<String> linesA, List<String> linesB) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(nameA).append("\n");
        sb.append("+++ ").append(nameB).append("\n");

        int[][] lcs = computeLCS(linesA, linesB);
        int i = 0, j = 0;
        while (i < linesA.size() && j < linesB.size()) {
            if (linesA.get(i).equals(linesB.get(j))) {
                i++;
                j++;
            } else {
                int startI = i, startJ = j;
                while (i < linesA.size()
                        && j < linesB.size()
                        && !linesA.get(i).equals(linesB.get(j))) {
                    i++;
                    j++;
                }
                if (startI < i) {
                    for (int k = startI; k < i; k++) {
                        sb.append("-").append(linesA.get(k)).append("\n");
                    }
                }
                if (startJ < j) {
                    for (int k = startJ; k < j; k++) {
                        sb.append("+").append(linesB.get(k)).append("\n");
                    }
                }
            }
        }
        // Remaining
        while (i < linesA.size()) {
            sb.append("-").append(linesA.get(i++)).append("\n");
        }
        while (j < linesB.size()) {
            sb.append("+").append(linesB.get(j++)).append("\n");
        }

        return sb.toString();
    }

    private int[][] computeLCS(List<String> a, List<String> b) {
        int m = a.size(), n = b.size();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp;
    }

    private Path resolveSafePath(String filePath) {
        Path userPath = Paths.get(filePath).normalize();
        Path resolved = sandboxRoot.resolve(userPath).normalize();
        if (!resolved.startsWith(sandboxRoot)) {
            throw new SecurityException("Path traversal: " + filePath);
        }
        return resolved;
    }

    private String getStringArg(ToolExecutionRequest request, String key) {
        Object val = request.getArguments().get(key);
        return val != null ? val.toString() : null;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
