package top.focess.veto.sandbox.capabilities;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.focess.veto.model.ToolExecutionRequest;
import top.focess.veto.sandbox.AtomicCapability;

/**
 * Atomic capability: compile_cpp_target. Compiles a C++ source file using a predefined compiler
 * toolchain. No arbitrary shell execution - only controlled compile invocation.
 */
@Component
public class CompileCppTargetCapability implements AtomicCapability {

  private static final Logger log = LoggerFactory.getLogger(CompileCppTargetCapability.class);

  private static final List<String> ALLOWED_SOURCE_EXTENSIONS =
      List.of(".cpp", ".c", ".cc", ".cxx");
  private static final long COMPILE_TIMEOUT_SECONDS = 120;
  private static final long MAX_SOURCE_SIZE = 1024 * 1024; // 1 MB

  private final Path sandboxRoot;
  private final String compilerPath;
  private final List<String> baseCompilerArgs;

  public CompileCppTargetCapability() {
    this.sandboxRoot =
        Paths.get(System.getProperty("veto.sandbox.tempDir", "./work/sandbox")).normalize();

    // Detect available compiler
    this.compilerPath = findCompiler();
    this.baseCompilerArgs =
        List.of(
            "-std=c++17",
            "-O2",
            "-Wall",
            "-Wextra",
            "-DNDEBUG",
            "-fPIC",
            "--sysroot=" + sandboxRoot.toString());
  }

  @Override
  public String getName() {
    return "compile_cpp_target";
  }

  @Override
  public void validate(ToolExecutionRequest request) throws SecurityException {
    String sourcePath = getStringArg(request, "sourcePath");
    if (sourcePath == null || sourcePath.isBlank()) {
      throw new IllegalArgumentException("'sourcePath' argument is required");
    }

    // Extension check
    String lower = sourcePath.toLowerCase();
    boolean allowedExt = ALLOWED_SOURCE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    if (!allowedExt) {
      throw new SecurityException("Not a C/C++ source file: " + sourcePath);
    }

    // Path traversal protection
    Path resolved = resolveSafePath(sourcePath);
    try {
      if (Files.size(resolved) > MAX_SOURCE_SIZE) {
        throw new SecurityException("Source file exceeds maximum size of 1MB");
      }
    } catch (IOException e) {
      throw new SecurityException("Cannot access source file: " + e.getMessage());
    }
  }

  @Override
  public String execute(ToolExecutionRequest request) throws SecurityException {
    validate(request);

    String sourcePath = getStringArg(request, "sourcePath");
    String outputName = getStringArg(request, "outputName");
    if (outputName == null || outputName.isBlank()) {
      outputName = "a.out";
    }

    // Additional compiler flags
    String extraFlags = getStringArg(request, "extraFlags");
    String includePaths = getStringArg(request, "includePaths");

    Path sourceResolved = resolveSafePath(sourcePath);
    Path outputDir = sandboxRoot.resolve("build");
    Path outputFile = outputDir.resolve(outputName);

    try {
      Files.createDirectories(outputDir);
    } catch (IOException e) {
      return "{\"status\":\"error\",\"message\":\"Cannot create build directory\"}";
    }

    log.info("C6 Sandbox: Compiling '{}' -> '{}'", sourceResolved, outputFile);

    List<String> command = new ArrayList<>();
    command.add(compilerPath);
    command.addAll(baseCompilerArgs);
    command.add(sourceResolved.toString());
    command.add("-o");
    command.add(outputFile.toString());

    if (extraFlags != null && !extraFlags.isBlank()) {
      // Strict validation: only allow known safe flags
      validateCompilerFlags(extraFlags);
      command.addAll(List.of(extraFlags.split("\\s+")));
    }

    if (includePaths != null && !includePaths.isBlank()) {
      for (String incPath : includePaths.split(":")) {
        Path incResolved = resolveSafePath(incPath.trim());
        command.add("-I" + incResolved.toString());
      }
    }

    try {
      ProcessBuilder pb = new ProcessBuilder(command);
      pb.directory(sandboxRoot.toFile());
      pb.environment().clear();

      Process process = pb.start();
      boolean finished = process.waitFor(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

      if (!finished) {
        process.destroyForcibly();
        return "{\"status\":\"error\",\"message\":\"Compilation timed out after "
            + COMPILE_TIMEOUT_SECONDS
            + "s\"}";
      }

      int exitCode = process.exitValue();
      String stdout = readStream(process.getInputStream());
      String stderr = readStream(process.getErrorStream());

      if (exitCode == 0) {
        log.info("C6 Sandbox: Compilation successful -> {}", outputFile);
        return String.format(
            "{\"status\":\"ok\",\"exitCode\":0,\"output\":\"%s\",\"binary\":\"%s\",\"size\":%d}",
            escapeJson(stdout), outputName, Files.size(outputFile));
      } else {
        log.warn("C6 Sandbox: Compilation failed (exit={})", exitCode);
        return String.format(
            "{\"status\":\"error\",\"exitCode\":%d,\"stdout\":\"%s\",\"stderr\":\"%s\"}",
            exitCode, escapeJson(stdout), escapeJson(stderr));
      }

    } catch (IOException e) {
      log.error("C6 Sandbox: Compilation I/O error", e);
      return "{\"status\":\"error\",\"message\":\"I/O error: " + escapeJson(e.getMessage()) + "\"}";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "{\"status\":\"error\",\"message\":\"Compilation interrupted\"}";
    }
  }

  private void validateCompilerFlags(String flags) {
    // Only allow -D, -I, -O flags for safety
    for (String flag : flags.split("\\s+")) {
      if (flag.startsWith("-D")
          || flag.startsWith("-I")
          || flag.startsWith("-O")
          || flag.startsWith("-std=")
          || flag.startsWith("-f")
          || flag.equals("-g")) {
        continue;
      }
      throw new SecurityException("Disallowed compiler flag: " + flag);
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

  private String findCompiler() {
    for (String candidate : List.of("g++", "clang++", "c++")) {
      try {
        Process p = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
        if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
          log.info("C6 Sandbox: Using compiler '{}'", candidate);
          return candidate;
        }
      } catch (Exception ignored) {
      }
    }
    log.warn("C6 Sandbox: No C++ compiler found. Install g++ or clang++.");
    return "g++"; // fallback
  }

  private String getStringArg(ToolExecutionRequest request, String key) {
    Object val = request.getArguments().get(key);
    return val != null ? val.toString() : null;
  }

  private String readStream(InputStream is) throws IOException {
    return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
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
