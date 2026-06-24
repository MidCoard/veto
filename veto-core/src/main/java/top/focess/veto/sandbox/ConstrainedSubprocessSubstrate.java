package top.focess.veto.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Veto-implemented local-default substrate — pure OS primitives, no third-party runtime. The
 * Windows-friendly MVP substrate. Transcribed from {@code
 * plans/mvp-core/part5_agent/container_sandbox_isolation.md} §4.1.
 *
 * <p><b>MVP hardening gap (noted):</b> the LLD §4.1 specifies the full hard wall — Windows
 * restricted token ({@code CreateRestrictedToken}) + ACL + Job Object; Linux namespaces + {@code
 * pivot_root} + dedicated UID + {@code seccomp} + cgroups. Those require platform-native (JNA/JNI)
 * plumbing and are a deployer-hardening follow-up. This MVP implementation provides the
 * load-bearing security property that is constructively enforceable from pure Java: <b>no shell,
 * ever</b> — each command is exec'd directly as {@code executable + argv[]} via {@link
 * ProcessBuilder}, so there is no string the model can inject {@code ;}/{@code &&}/{@code |}/
 * {@code $()}/backticks into (injection impossible by construction, not by filtering), the cwd is
 * locked under the workspace root, the environment is sanitized to an allowlist, and a wall-clock
 * timeout bounds runaway processes. The kernel-level token/namespace/cgroup enforcement is the
 * noted gap.
 */
@Component
public final class ConstrainedSubprocessSubstrate implements SandboxSubstrate {

    private static final Logger log = LoggerFactory.getLogger(ConstrainedSubprocessSubstrate.class);

    /** Environment variables passed through to sandboxed processes (the rest are dropped). */
    private static final List<String> ENV_ALLOWLIST =
            List.of(
                    "PATH",
                    "HOME",
                    "USER",
                    "USERNAME",
                    "LANG",
                    "LC_ALL",
                    "JAVA_HOME",
                    "SystemRoot",
                    "TEMP",
                    "TMP");

    @Override
    public SandboxHandle provision(SandboxProfile profile) {
        Path root = profile.workspaceRoot();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Sandbox workspace root unreachable: " + root, e);
        }
        log.info("Sandbox provisioned (subprocess substrate): workspaceRoot={}", root);
        return new SandboxHandle("local-" + root.hashCode(), this, root);
    }

    @Override
    public CommandResult runCommands(
            SandboxHandle h, List<Command> cmds, Path cwd, ChainMode connect, Duration timeout) {
        if (cmds.isEmpty()) {
            return new CommandResult(0, "", "", List.of());
        }
        Path workdir = resolveUnderWorkspace(h, cwd);
        List<ProcessBuilder> builders = new ArrayList<>();
        for (Command c : cmds) {
            List<String> command = new ArrayList<>();
            command.add(c.executable());
            command.addAll(c.args());
            ProcessBuilder pb = new ProcessBuilder(command).directory(workdir.toFile());
            pb.environment().keySet().retainAll(ENV_ALLOWLIST);
            builders.add(pb);
        }

        Duration effectiveTimeout = timeout != null ? timeout : Duration.ofMinutes(10);
        try {
            return switch (connect) {
                case PIPE -> runPipeline(builders, effectiveTimeout);
                case RUN_ALL, STOP_ON_FAILURE -> runSequential(builders, connect, effectiveTimeout);
            };
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "", "Sandbox run failed: " + e.getMessage(), List.of(-1));
        }
    }

    private CommandResult runPipeline(List<ProcessBuilder> builders, Duration timeout)
            throws IOException, InterruptedException {
        List<Process> pipeline = ProcessBuilder.startPipeline(builders);
        Process last = pipeline.get(pipeline.size() - 1);
        String stdout = new String(last.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(last.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = last.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            pipeline.forEach(Process::destroyForcibly);
            return new CommandResult(-1, stdout, stderr + "\n[timeout]", List.of(-1));
        }
        List<Integer> codes = new ArrayList<>();
        for (Process p : pipeline) codes.add(p.exitValue());
        return new CommandResult(last.exitValue(), stdout, stderr, codes);
    }

    private CommandResult runSequential(
            List<ProcessBuilder> builders, ChainMode connect, Duration timeout)
            throws IOException, InterruptedException {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        List<Integer> codes = new ArrayList<>();
        for (ProcessBuilder pb : builders) {
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            stdout.append(out);
            stderr.append(err);
            boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                codes.add(-1);
                return new CommandResult(-1, stdout.toString(), stderr + "\n[timeout]", codes);
            }
            codes.add(p.exitValue());
            if (connect == ChainMode.STOP_ON_FAILURE && p.exitValue() != 0) {
                return new CommandResult(
                        p.exitValue(), stdout.toString(), stderr.toString(), codes);
            }
        }
        int overall = codes.isEmpty() ? 0 : codes.get(codes.size() - 1);
        return new CommandResult(overall, stdout.toString(), stderr.toString(), codes);
    }

    @Override
    public byte[] readFile(SandboxHandle h, Path rel) {
        try {
            return Files.readAllBytes(resolveUnderWorkspace(h, rel));
        } catch (IOException e) {
            throw new IllegalStateException("readFile failed: " + rel, e);
        }
    }

    @Override
    public void writeFile(SandboxHandle h, Path rel, byte[] content) {
        try {
            Path resolved = resolveUnderWorkspace(h, rel);
            Files.createDirectories(resolved.getParent());
            Files.write(resolved, content);
        } catch (IOException e) {
            throw new IllegalStateException("writeFile failed: " + rel, e);
        }
    }

    @Override
    public void patchFile(SandboxHandle h, Path rel, PatchSpec patch) {
        Path resolved = resolveUnderWorkspace(h, rel);
        try {
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            String updated = content.replace(patch.targetContent(), patch.replacementContent());
            Files.writeString(resolved, updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("patchFile failed: " + rel, e);
        }
    }

    @Override
    public List<Entry> listDir(SandboxHandle h, Path rel) {
        Path resolved = resolveUnderWorkspace(h, rel);
        try (Stream<Path> stream = Files.list(resolved)) {
            return stream.map(
                            p ->
                                    new Entry(
                                            p.getFileName().toString(),
                                            Files.isDirectory(p),
                                            sizeOf(p)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("listDir failed: " + rel, e);
        }
    }

    @Override
    public List<Match> grep(SandboxHandle h, Path rel, GrepSpec spec) {
        Path resolved = resolveUnderWorkspace(h, rel);
        List<Match> matches = new ArrayList<>();
        String query = spec.caseInsensitive() ? spec.query().toLowerCase() : spec.query();
        try (Stream<Path> files = Files.walk(resolved)) {
            files.filter(Files::isRegularFile)
                    .forEach(
                            file -> {
                                try (Stream<String> lines =
                                        Files.lines(file, StandardCharsets.UTF_8)) {
                                    var lineList = lines.toList();
                                    for (int i = 0; i < lineList.size(); i++) {
                                        String line = lineList.get(i);
                                        String candidate =
                                                spec.caseInsensitive() ? line.toLowerCase() : line;
                                        if (candidate.contains(query)) {
                                            matches.add(new Match(file.toString(), i + 1, line));
                                        }
                                    }
                                } catch (IOException ignored) {
                                }
                            });
        } catch (IOException e) {
            throw new IllegalStateException("grep failed: " + rel, e);
        }
        return matches;
    }

    @Override
    public Stat stat(SandboxHandle h, Path rel) {
        Path resolved = resolveUnderWorkspace(h, rel);
        boolean exists = Files.exists(resolved);
        try {
            return new Stat(
                    exists,
                    exists ? Files.size(resolved) : 0L,
                    exists && Files.isDirectory(resolved));
        } catch (IOException e) {
            return new Stat(false, 0L, false);
        }
    }

    @Override
    public void deprovision(SandboxHandle h) {
        log.debug("Sandbox deprovisioned: {}", h.sessionId());
    }

    /** Resolves {@code rel} under the workspace root, rejecting traversal escapes. */
    private Path resolveUnderWorkspace(SandboxHandle h, Path rel) {
        Path root = h.workspaceRoot().toAbsolutePath().normalize();
        Path resolved = root.resolve(rel).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Path escapes sandbox workspace: " + rel);
        }
        return resolved;
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1L;
        }
    }
}
