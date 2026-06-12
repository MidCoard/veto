package top.focess.veto.sandbox.capabilities;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.model.ToolExecutionRequest;
import top.focess.veto.sandbox.AtomicCapability;

/**
 * Atomic capability: search_in_files. Searches for a pattern in text files within the sandbox.
 * Strictly limits scope and prevents regex injection attacks.
 */
@Component
public class SearchInFilesCapability implements AtomicCapability {

    private static final Logger log = LoggerFactory.getLogger(SearchInFilesCapability.class);

    private static final int MAX_RESULTS = 100;
    private static final int MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB search limits

    private final Path sandboxRoot;

    public SearchInFilesCapability() {
        this.sandboxRoot =
                Paths.get(System.getProperty("veto.sandbox.tempDir", "./work/sandbox")).normalize();
    }

    @Override
    public String getName() {
        return "search_in_files";
    }

    @Override
    public void validate(ToolExecutionRequest request) throws SecurityException {
        String pattern = getStringArg(request, "pattern");
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("'pattern' argument is required");
        }

        // Limit pattern length to prevent ReDoS
        if (pattern.length() > 200) {
            throw new SecurityException("Search pattern exceeds maximum length of 200 characters");
        }
    }

    @Override
    public String execute(ToolExecutionRequest request) throws SecurityException {
        validate(request);

        String pattern = getStringArg(request, "pattern");
        String dirPath = getStringArg(request, "dirPath");
        String fileGlob = getStringArg(request, "fileGlob");

        Path searchDir =
                dirPath != null && !dirPath.isEmpty() ? resolveSafePath(dirPath) : sandboxRoot;

        log.info("C6 Sandbox: Searching for '{}' in {}", pattern, searchDir);

        List<Map<String, Object>> results = new ArrayList<>();

        try {
            PathMatcher matcher =
                    fileGlob != null && !fileGlob.isEmpty()
                            ? FileSystems.getDefault().getPathMatcher("glob:" + fileGlob)
                            : null;

            try (var stream = Files.walk(searchDir, 5)) {
                stream.filter(Files::isRegularFile)
                        .filter(
                                p -> {
                                    try {
                                        return Files.size(p) <= MAX_FILE_SIZE;
                                    } catch (IOException e) {
                                        return false;
                                    }
                                })
                        .filter(p -> matcher == null || matcher.matches(p.getFileName()))
                        .forEach(file -> searchInFile(file, pattern, results));
            }
        } catch (IOException e) {
            log.error("C6 Sandbox: Search I/O error", e);
            return "{\"status\":\"error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}";
        }

        String resultJson =
                results.stream()
                        .map(
                                r ->
                                        String.format(
                                                "{\"file\":\"%s\",\"line\":%d,\"content\":\"%s\"}",
                                                escapeJson((String) r.get("file")),
                                                r.get("line"),
                                                escapeJson((String) r.get("content"))))
                        .collect(Collectors.joining(",", "[", "]"));

        return String.format(
                "{\"status\":\"ok\",\"pattern\":\"%s\",\"total\":%d,\"results\":%s}",
                escapeJson(pattern), results.size(), resultJson);
    }

    private void searchInFile(Path file, String pattern, List<Map<String, Object>> results) {
        if (results.size() >= MAX_RESULTS) return;

        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size() && results.size() < MAX_RESULTS; i++) {
                if (lines.get(i).toLowerCase().contains(pattern.toLowerCase())) {
                    Map<String, Object> match = new LinkedHashMap<>();
                    match.put("file", sandboxRoot.relativize(file).toString());
                    match.put("line", i + 1);
                    String truncated =
                            lines.get(i).length() > 200
                                    ? lines.get(i).substring(0, 200) + "..."
                                    : lines.get(i);
                    match.put("content", truncated);
                    results.add(match);
                }
            }
        } catch (IOException e) {
            log.debug("C6 Sandbox: Could not read '{}' for search", file);
        }
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
