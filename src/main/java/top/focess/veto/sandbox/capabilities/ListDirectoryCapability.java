package top.focess.veto.sandbox.capabilities;

import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.model.ToolExecutionRequest;
import top.focess.veto.sandbox.AtomicCapability;

/**
 * Atomic capability: list_directory. Lists contents of a directory with path traversal protection.
 */
@Component
public class ListDirectoryCapability implements AtomicCapability {

  private static final Logger log = LoggerFactory.getLogger(ListDirectoryCapability.class);

  private final Path sandboxRoot;

  public ListDirectoryCapability() {
    this.sandboxRoot =
        Paths.get(System.getProperty("veto.sandbox.tempDir", "./work/sandbox")).normalize();
  }

  @Override
  public String getName() {
    return "list_directory";
  }

  @Override
  public void validate(ToolExecutionRequest request) throws SecurityException {
    String dirPath = getStringArg(request, "dirPath");
    if (dirPath == null || dirPath.isBlank()) {
      throw new IllegalArgumentException("'dirPath' argument is required");
    }

    Path resolved = resolveSafePath(dirPath);
    if (!Files.exists(resolved)) {
      throw new IllegalArgumentException("Directory not found: " + resolved);
    }
    if (!Files.isDirectory(resolved)) {
      throw new IllegalArgumentException("Path is not a directory: " + resolved);
    }
  }

  @Override
  public String execute(ToolExecutionRequest request) throws SecurityException {
    validate(request);
    String dirPath = getStringArg(request, "dirPath");
    boolean recursive = "true".equals(getStringArg(request, "recursive"));

    Path resolved = resolveSafePath(dirPath);
    log.info("C6 Sandbox: Listing directory '{}'", resolved);

    try (var stream = recursive ? Files.walk(resolved, 3) : Files.list(resolved)) {
      String listing =
          stream
              .map(
                  p -> {
                    String relative = resolved.relativize(p).toString();
                    String type = Files.isDirectory(p) ? "dir" : "file";
                    String size = "file".equals(type) ? " (" + getSize(p) + ")" : "";
                    return relative + (relative.isEmpty() ? "/" : "") + " [" + type + "]" + size;
                  })
              .collect(Collectors.joining("\n"));

      if (listing.isEmpty()) {
        listing = "(empty directory)";
      }

      return String.format(
          "{\"status\":\"ok\",\"directory\":\"%s\",\"contents\":\"%s\"}",
          escapeJson(dirPath), escapeJson(listing));
    } catch (IOException e) {
      log.error("C6 Sandbox: Failed to list directory", e);
      return "{\"status\":\"error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}";
    }
  }

  private String getSize(Path p) {
    try {
      long bytes = Files.size(p);
      if (bytes < 1024) return bytes + " B";
      if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
      return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    } catch (IOException e) {
      return "?";
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
