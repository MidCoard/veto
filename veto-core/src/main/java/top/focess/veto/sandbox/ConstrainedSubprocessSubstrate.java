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
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The Veto-implemented local-default substrate — pure OS primitives, no third-party runtime.
 *
 * <p>The substrate specifies the full hard wall — Windows restricted token ({@code
 * CreateRestrictedToken}) + ACL + Job Object; Linux namespaces + {@code pivot_root} + dedicated UID
 * + {@code seccomp} + cgroups. Those require platform-native (JNA/JNI) plumbing and are a
 * deployer-hardening follow-up. This implementation provides the load-bearing security property
 * that is constructively enforceable from pure Java: <b>no shell, ever</b> — each command is exec'd
 * directly as {@code executable + argv[]} via {@link ProcessBuilder}, so there is no string the
 * model can inject {@code;}/{@code &&}/{@code |}/ {@code $}/backticks into (injection impossible by
 * construction, not by filtering), the cwd is locked under the workspace root, the environment is
 * sanitized to an allowlist, and a wall-clock timeout bounds runaway processes. The kernel-level
 * token/namespace/cgroup enforcement is a deployer follow-up.
 */
@Component
public final class ConstrainedSubprocessSubstrate implements SandboxSubstrate {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.sandbox.ConstrainedSubprocessSubstrate");

    /**
     * Environment variables passed through to sandboxed processes (the rest are dropped). Kept to
     * standard, non-secret system + toolchain vars so real CLIs work: node/npm on Windows need
     * {@code ComSpec}/{@code PATHEXT} to run {@code npm.cmd} and {@code APPDATA}/{@code
     * LOCALAPPDATA}/{@code ProgramFiles}/{@code USERPROFILE} to resolve the node install and the
     * project - without them {@code npm run dev} dies immediately (observed: exit 1 in ~9ms). No
     * credential-bearing vars are on the list.
     */
    private static final @NonNull List<String> ENV_ALLOWLIST =
            List.of(
                    "PATH",
                    "HOME",
                    "USER",
                    "USERNAME",
                    "LANG",
                    "LC_ALL",
                    "JAVA_HOME",
                    "SystemRoot",
                    "SYSTEMROOT",
                    "TEMP",
                    "TMP",
                    // Windows shell + node/npm toolchain resolution.
                    "ComSpec",
                    "PATHEXT",
                    "APPDATA",
                    "LOCALAPPDATA",
                    "ProgramFiles",
                    "ProgramFiles(x86)",
                    "ProgramData",
                    "USERPROFILE",
                    "HOMEDRIVE",
                    "HOMEPATH",
                    "SYSTEMDRIVE",
                    "PUBLIC",
                    "windir");

    /**
     * The kernel-level wall (Windows Job Object / Linux cgroup) attached to each spawned process.
     * Nullable: the no-arg constructor (tests) leaves it null so attach is skipped; Spring injects
     * the {@link KernelSandboxSubstrate} bean in production so the wall applies to spawned
     * commands.
     */
    private final KernelSandboxSubstrate kernelWall;

    /** No-arg constructor (tests): no kernel wall — attach is skipped. */
    public ConstrainedSubprocessSubstrate() {
        this.kernelWall = null;
    }

    /**
     * Spring constructor: injects the kernel wall so spawned processes are attached in production.
     */
    @Autowired
    public ConstrainedSubprocessSubstrate(@NonNull KernelSandboxSubstrate kernelWall) {
        this.kernelWall = kernelWall;
    }

    @Override
    public @NonNull SandboxHandle provision(@NonNull SandboxProfile profile) {
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
    public @NonNull CommandResult runCommands(
            @NonNull SandboxHandle h,
            @NonNull List<Command> cmds,
            @NonNull Path cwd,
            @NonNull ChainMode connect,
            @NonNull Duration timeout) {
        if (cmds.isEmpty()) {
            return new CommandResult(0, "", "", List.of());
        }
        Path workdir = resolveUnderWorkspace(h, cwd);
        List<ProcessBuilder> builders = new ArrayList<>();
        for (Command c : cmds) {
            builders.add(processBuilderFor(c, workdir));
        }

        Duration effectiveTimeout = timeout;
        try {
            return switch (connect) {
                case PIPE -> runPipeline(builders, effectiveTimeout);
                case RUN_ALL, STOP_ON_FAILURE -> runSequential(builders, connect, effectiveTimeout);
            };
        } catch (InterruptedException e) {
            // A genuine interrupt (cancel/shutdown): restore the flag so the loop sees it.
            Thread.currentThread().interrupt();
            return new CommandResult(
                    -1, "", "Sandbox run interrupted: " + e.getMessage(), List.of(-1));
        } catch (IOException e) {
            // A plain spawn/IO failure (executable not found, cwd unreadable) is DATA for the
            // agent to judge, not an interrupt. Do NOT touch the interrupt flag here - setting it
            // poisons every later interruptible op on this thread (the PG turn-log write fails as
            // "I/O error", the next LLM HTTP call throws InterruptedException instantly, and the
            // whole round dies on a phantom interrupt).
            return new CommandResult(-1, "", "Sandbox run failed: " + e.getMessage(), List.of(-1));
        }
    }

    @Override
    public @NonNull Process startBackground(
            @NonNull SandboxHandle h, @NonNull Command cmd, @NonNull Path cwd) {
        Path workdir = resolveUnderWorkspace(h, cwd);
        ProcessBuilder pb = processBuilderFor(cmd, workdir);
        // Merge stderr into stdout so a single drain thread captures everything the server logs.
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            attachKernelWall(p);
            return p;
        } catch (IOException e) {
            throw new IllegalStateException("Background start failed: " + cmd.executable(), e);
        }
    }

    private @NonNull ProcessBuilder processBuilderFor(@NonNull Command c, @NonNull Path workdir) {
        List<String> command = new ArrayList<>();
        command.add(resolveExecutable(c.executable()));
        command.addAll(c.args());
        ProcessBuilder pb = new ProcessBuilder(command).directory(workdir.toFile());
        pb.environment().keySet().retainAll(ENV_ALLOWLIST);
        // Subprocess output is piped, so color-capable CLIs should emit plain text at the
        // source: NO_COLOR is the no-color.org convention (picocolors/chalk/vite/npm honor it),
        // FORCE_COLOR=0 covers tools that only read FORCE_COLOR. AnsiEscapes.strip at the decode
        // seam catches the tools that ignore both.
        pb.environment().put("NO_COLOR", "1");
        pb.environment().put("FORCE_COLOR", "0");
        return pb;
    }

    /**
     * Resolves a bare executable name against PATH × PATHEXT the way a shell would. Windows process
     * creation only appends {@code .exe} to a bare name, so extensionless shims like {@code npm}
     * (really {@code npm.cmd}) fail to launch unless resolved here — that drove the agent to hunt
     * full paths via {@code cmd.exe}. Names that already carry a path separator or a file extension
     * pass through untouched (the OS runs them as given). Unresolved bare names also pass through
     * so the spawn fails with the standard "not found" error for the model to read. Screening is
     * unaffected: it normalizes the ORIGINAL name (baseName + extension strip) before allowlist
     * matching.
     */
    private static @NonNull String resolveExecutable(@NonNull String executable) {
        if (executable.indexOf('/') >= 0 || executable.indexOf('\\') >= 0) {
            return executable; // explicit path — run as given
        }
        if (executable.lastIndexOf('.') > 0) {
            return executable; // already has an extension — let the OS resolve it
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return executable;
        }
        String pathext = System.getenv("PATHEXT");
        List<String> extensions =
                (pathext == null || pathext.isBlank())
                        ? List.of(".com", ".exe", ".bat", ".cmd")
                        : java.util.Arrays.stream(pathext.split(";"))
                                .map(String::trim)
                                .filter(e -> !e.isEmpty())
                                .map(String::toLowerCase)
                                .toList();
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String ext : extensions) {
                Path candidate = Path.of(dir, executable + ext);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return executable;
    }

    /**
     * Waits for {@code p} respecting the timeout. A zero/negative {@code timeout} means "no cap" -
     * blocks until the process exits naturally (used by {@code run_command(timeout=0)}). Returns
     * {@code false} only when the cap elapses before exit.
     */
    private static boolean waitForCap(@NonNull Process p, @NonNull Duration timeout)
            throws InterruptedException {
        if (timeout.isZero() || timeout.isNegative()) {
            p.waitFor();
            return true;
        }
        return p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private @NonNull CommandResult runPipeline(
            @NonNull List<ProcessBuilder> builders, @NonNull Duration timeout)
            throws IOException, InterruptedException {
        List<Process> pipeline = ProcessBuilder.startPipeline(builders);
        pipeline.forEach(this::attachKernelWall);
        Process last = pipeline.get(pipeline.size() - 1);
        CappedWait wait = waitCapped(last, timeout);
        String stdout = wait.stdout();
        String stderr = wait.stderr();
        if (!wait.finished()) {
            pipeline.forEach(Process::destroyForcibly);
            return new CommandResult(-1, stdout, stderr + "\n[timeout]", List.of(-1));
        }
        List<Integer> codes = new ArrayList<>();
        for (Process p : pipeline) codes.add(p.exitValue());
        return new CommandResult(last.exitValue(), stdout, stderr, codes);
    }

    private @NonNull CommandResult runSequential(
            @NonNull List<ProcessBuilder> builders,
            @NonNull ChainMode connect,
            @NonNull Duration timeout)
            throws IOException, InterruptedException {
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        List<Integer> codes = new ArrayList<>();
        // One deadline for the whole chain: `timeout` bounds the blocking wait of the call, so a
        // 3-command chain with timeout=90 gets 90s TOTAL, not 90s each. Zero/negative = no cap.
        long deadlineNanos =
                timeout.isZero() || timeout.isNegative()
                        ? Long.MAX_VALUE
                        : System.nanoTime() + timeout.toNanos();
        boolean capped = !timeout.isZero() && !timeout.isNegative();
        for (ProcessBuilder pb : builders) {
            Duration remaining =
                    capped ? Duration.ofNanos(deadlineNanos - System.nanoTime()) : timeout;
            if (capped && remaining.isNegative()) {
                codes.add(-1);
                return new CommandResult(-1, stdout.toString(), stderr + "\n[timeout]", codes);
            }
            Process p = pb.start();
            attachKernelWall(p);
            CappedWait wait = waitCapped(p, remaining);
            stdout.append(wait.stdout());
            stderr.append(wait.stderr());
            if (!wait.finished()) {
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

    /** A capped wait plus the output drained while waiting. */
    private record CappedWait(boolean finished, @NonNull String stdout, @NonNull String stderr) {}

    /**
     * Waits for {@code p} under the timeout cap while draining stdout/stderr concurrently. The
     * streams only reach EOF when the process exits, so reading them must run alongside the capped
     * wait — reading them first would block until exit and silently void the cap (a runaway `dir
     * /s` scan held an agent thread for minutes past its timeout this way). On cap expiry the
     * process is force-killed (the kernel wall takes its process tree with it), the drains observe
     * EOF and join, and whatever output landed is returned with {@code finished=false}.
     */
    private @NonNull CappedWait waitCapped(@NonNull Process p, @NonNull Duration timeout)
            throws InterruptedException {
        // Raw bytes first, decoded once at the end: a multi-byte character can straddle two read
        // chunks, and the writer population is mixed — console CLIs emit the platform codepage,
        // node/python emit UTF-8 — so {@link SubprocessOutput} sniffs per buffer.
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        Thread outDrain =
                Thread.ofVirtual()
                        .name("sandbox-drain-out")
                        .start(() -> drain(p.getInputStream(), out));
        Thread errDrain =
                Thread.ofVirtual()
                        .name("sandbox-drain-err")
                        .start(() -> drain(p.getErrorStream(), err));
        boolean finished = waitForCap(p, timeout);
        if (!finished) {
            p.destroyForcibly();
            p.waitFor(); // reap: streams close, the drain threads observe EOF and exit
        }
        outDrain.join();
        errDrain.join();
        // Strip ANSI/VT escapes at the decode seam: the agent's context, the persisted history,
        // and the UI ledger all consume this same string, and escape bytes are noise to all three.
        return new CappedWait(
                finished,
                AnsiEscapes.strip(SubprocessOutput.decode(out.toByteArray())),
                AnsiEscapes.strip(SubprocessOutput.decode(err.toByteArray())));
    }

    /** One writer (the drain thread) per buffer; the main thread reads only after join(). */
    private static void drain(
            java.io.@NonNull InputStream in, java.io.@NonNull ByteArrayOutputStream sink) {
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                sink.write(buf, 0, n);
            }
        } catch (IOException e) {
            // The process was killed or the pipe broke mid-read — the captured prefix is the
            // output; the [timeout]/exit annotation on the result carries the rest of the story.
        }
    }

    /**
     * Best-effort attach the kernel wall to a spawned process; skipped when no wall is configured.
     */
    private void attachKernelWall(@NonNull Process process) {
        if (kernelWall == null || !kernelWall.isAvailable()) {
            return;
        }
        try {
            kernelWall.attach(process);
        } catch (Throwable t) {
            // The wall is best-effort hardening — a failure to attach must not break the run.
            log.debug(
                    "ConstrainedSubprocessSubstrate: kernel-wall attach failed: {}",
                    String.valueOf(t.getMessage()));
        }
    }

    @Override
    public byte @NonNull [] readFile(@NonNull SandboxHandle h, @NonNull Path rel) {
        try {
            return Files.readAllBytes(resolveUnderWorkspace(h, rel));
        } catch (IOException e) {
            throw new IllegalStateException("readFile failed: " + rel, e);
        }
    }

    @Override
    public void writeFile(@NonNull SandboxHandle h, @NonNull Path rel, byte @NonNull [] content) {
        try {
            Path resolved = resolveUnderWorkspace(h, rel);
            Path parent = resolved.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(resolved, content);
        } catch (IOException e) {
            throw new IllegalStateException("writeFile failed: " + rel, e);
        }
    }

    @Override
    public void patchFile(@NonNull SandboxHandle h, @NonNull Path rel, @NonNull PatchSpec patch) {
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
    public @NonNull List<Entry> listDir(@NonNull SandboxHandle h, @NonNull Path rel) {
        Path resolved = resolveUnderWorkspace(h, rel);
        try (Stream<Path> stream = Files.list(resolved)) {
            return stream.map(
                            p -> {
                                Path fileName = p.getFileName();
                                String name = fileName == null ? p.toString() : fileName.toString();
                                return new Entry(name, Files.isDirectory(p), sizeOf(p));
                            })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("listDir failed: " + rel, e);
        }
    }

    @Override
    public @NonNull List<Match> grep(
            @NonNull SandboxHandle h, @NonNull Path rel, @NonNull GrepSpec spec) {
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
    public @NonNull Stat stat(@NonNull SandboxHandle h, @NonNull Path rel) {
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
    public void deprovision(@NonNull SandboxHandle h) {
        log.debug("Sandbox deprovisioned: {}", h.sessionId());
    }

    /** Resolves {@code rel} under the workspace root, rejecting traversal escapes. */
    private @NonNull Path resolveUnderWorkspace(@NonNull SandboxHandle h, @NonNull Path rel) {
        Path root = h.workspaceRoot().toAbsolutePath().normalize();
        Path resolved = root.resolve(rel).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Path escapes sandbox workspace: " + rel);
        }
        return resolved;
    }

    private static long sizeOf(@NonNull Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1L;
        }
    }
}
