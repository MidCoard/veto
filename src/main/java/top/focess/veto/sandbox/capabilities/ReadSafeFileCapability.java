package top.focess.veto.sandbox.capabilities;

import top.focess.veto.model.ToolExecutionRequest;
import top.focess.veto.sandbox.AtomicCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Atomic capability: read_safe_file.
 * Reads a file's contents with strict path traversal protection.
 * Only allows reading files within the sandbox working directory.
 */
@Component
public class ReadSafeFileCapability implements AtomicCapability {

    private static final Logger log = LoggerFactory.getLogger(ReadSafeFileCapability.class);

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final List<String> ALLOWED_EXTENSIONS = List.of(
        ".txt", ".md", ".json", ".yaml", ".yml", ".xml", ".csv",
        ".java", ".kt", ".cpp", ".c", ".h", ".hpp", ".py", ".js", ".ts",
        ".html", ".css", ".sh", ".bat", ".ps1", ".properties", ".cfg",
        ".log", ".toml", ".gradle", ".kts", ".sql", ".proto"
    );

    private final Path sandboxRoot;

    public ReadSafeFileCapability() {
        this.sandboxRoot = Paths.get(System.getProperty("veto.sandbox.tempDir", "./work/sandbox")).normalize();
    }

    @Override
    public String getName() {
        return "read_safe_file";
    }

    @Override
    public void validate(ToolExecutionRequest request) throws SecurityException {
        String filePath = getStringArg(request, "filePath");
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("'filePath' argument is required");
        }

        // Path traversal protection
        Path resolved = resolveSafePath(filePath);

        // Extension check
        String fileName = resolved.getFileName().toString().toLowerCase();
        boolean allowed = ALLOWED_EXTENSIONS.stream().anyMatch(fileName::endsWith);
        if (!allowed) {
            throw new SecurityException("File extension not allowed: " + fileName);
        }

        // Size check
        try {
            if (Files.size(resolved) > MAX_FILE_SIZE) {
                throw new SecurityException("File exceeds maximum size of 10MB");
            }
        } catch (IOException e) {
            throw new SecurityException("Cannot access file: " + e.getMessage());
        }
    }

    @Override
    public String execute(ToolExecutionRequest request) throws SecurityException {
        validate(request);

        String filePath = getStringArg(request, "filePath");
        Path resolved = resolveSafePath(filePath);

        log.info("C6 Sandbox: Reading file '{}' (resolved={})", filePath, resolved);

        try {
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            return String.format(
                "{\"status\":\"ok\",\"file\":\"%s\",\"size\":%d,\"content\":%s}",
                resolved.getFileName().toString(),
                content.length(),
                escapeJson(content)
            );
        } catch (IOException e) {
            log.error("C6 Sandbox: Failed to read file '{}'", resolved, e);
            return "{\"status\":\"error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * Resolve a file path against the sandbox root, preventing traversal attacks.
     */
    private Path resolveSafePath(String filePath) {
        Path userPath = Paths.get(filePath).normalize();
        Path resolved = sandboxRoot.resolve(userPath).normalize();

        if (!resolved.startsWith(sandboxRoot)) {
            throw new SecurityException("Path traversal detected: " + filePath);
        }

        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("File not found: " + resolved);
        }

        if (Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("Path is a directory, not a file: " + resolved);
        }

        return resolved;
    }

    private String getStringArg(ToolExecutionRequest request, String key) {
        Object val = request.getArguments().get(key);
        return val != null ? val.toString() : null;
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
