package top.focess.veto.sandbox;

import static top.focess.veto.util.LogValues.safe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The Veto-orchestrated local-default substrate.
 *
 * <p>The implementation constructively provides argv-only execution, a canonical cwd, the host
 * terminal environment, output capture, and wall-clock timeouts. Windows uses a gated bootstrap
 * before Job attachment and launches the target in a zero-capability AppContainer; macOS uses a
 * pre-exec Seatbelt profile; Linux uses Bubblewrap namespaces/mounts followed by an inner {@code
 * no_new_privs}/seccomp stage. Windows keeps the environment for lookup and lazily projects the
 * selected executable's containing root into the AppContainer without classifying that program as
 * trusted; Linux cgroup resource enforcement remains incomplete and must not be inferred from this
 * class's name.
 */
@Component
public final class ConstrainedSubprocessSubstrate implements SandboxSubstrate {

    private static final int MAX_CAPTURE_BYTES_PER_STREAM = 4 * 1024 * 1024;
    private static final @NonNull String SANDBOX_TEMP_DIRECTORY = ".veto/sandbox-tmp";

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.sandbox.ConstrainedSubprocessSubstrate");

    /**
     * The host-OS wall. The no-arg test constructor leaves it null; Spring always injects the
     * production implementation.
     */
    private final KernelSandboxSubstrate kernelWall;

    /** Test/local constructor without an OS wall; production construction supplies the wall. */
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
        if (kernelWall != null && !kernelWall.isAvailable()) {
            throw new IllegalStateException(
                    "The configured OS sandbox is unavailable; refusing an unsandboxed session");
        }
        Path root = profile.workspaceRoot();
        try {
            Files.createDirectories(root);
            Files.createDirectories(root.resolve(SANDBOX_TEMP_DIRECTORY));
            if (kernelWall != null) {
                kernelWall.provisionWorkspace(profile);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Sandbox workspace root unreachable: " + root, e);
        }
        log.info("Sandbox provisioned (subprocess substrate): workspaceRoot={}", root);
        return new SandboxHandle("local-" + root.hashCode(), this, root, profile);
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
        Duration effectiveTimeout = boundedTimeout(timeout, h.profile().maxWallClock());
        Path workdir = resolveUnderWorkspace(h, cwd);
        List<PreparedProcess> builders = new ArrayList<>();
        for (Command c : cmds) {
            builders.add(processBuilderFor(c, workdir, h.profile()));
        }

        try {
            try {
                return switch (connect) {
                    case PIPE -> runPipeline(builders, effectiveTimeout, h.profile());
                    case RUN_ALL, STOP_ON_FAILURE ->
                            runSequential(builders, connect, effectiveTimeout, h.profile());
                };
            } finally {
                builders.forEach(PreparedProcess::close);
            }
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

    private static @NonNull Duration boundedTimeout(
            @NonNull Duration requested, @NonNull Duration profileMaximum) {
        if (requested.isZero()
                || requested.isNegative()
                || requested.compareTo(profileMaximum) > 0) {
            return profileMaximum;
        }
        return requested;
    }

    @Override
    public @NonNull Process startBackground(
            @NonNull SandboxHandle h, @NonNull Command cmd, @NonNull Path cwd) {
        Path workdir = resolveUnderWorkspace(h, cwd);
        PreparedProcess prepared = processBuilderFor(cmd, workdir, h.profile());
        ProcessBuilder pb = prepared.builder();
        // Merge stderr into stdout so a single drain thread captures everything the server logs.
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            establishKernelWall(p, prepared, h.profile());
            return p;
        } catch (IOException e) {
            prepared.close();
            throw new IllegalStateException("Background start failed: " + cmd.executable(), e);
        }
    }

    private @NonNull PreparedProcess processBuilderFor(
            @NonNull Command c, @NonNull Path workdir, @NonNull SandboxProfile profile) {
        List<String> command = new ArrayList<>();
        String executable = resolveExecutable(c.executable(), workdir);
        if (isWindowsCommandShim(executable)) {
            validateWindowsCommandShimToken(executable);
            c.args().forEach(ConstrainedSubprocessSubstrate::validateWindowsCommandShimToken);
            String commandInterpreter = System.getenv("ComSpec");
            if (commandInterpreter == null || commandInterpreter.isBlank()) {
                commandInterpreter = "cmd.exe";
            }
            command.add(resolveExecutable(commandInterpreter, workdir));
            command.add("/d");
            command.add("/c");
            command.add("call");
            command.add(executable);
            command.addAll(c.args());
        } else {
            command.add(executable);
            command.addAll(c.args());
        }
        KernelSandboxSubstrate.PreparedCommand prepared = null;
        List<String> launchCommand = command;
        if (kernelWall != null && kernelWall.isAvailable()) {
            prepared = kernelWall.prepareCommand(command, profile, workdir);
            launchCommand = prepared.command();
        }
        ProcessBuilder pb = new ProcessBuilder(launchCommand).directory(workdir.toFile());
        configureEnvironment(pb.environment(), profile);
        return new PreparedProcess(pb, prepared);
    }

    /**
     * Preserve the complete host environment so direct executable discovery and toolchain behavior
     * match the user's normal terminal. Only presentation flags and the sandbox-owned temporary
     * directory are overridden.
     */
    static void configureEnvironment(
            @NonNull Map<String, String> environment, @NonNull SandboxProfile profile) {
        // Subprocess output is piped, so color-capable CLIs should emit plain text at the
        // source: NO_COLOR is the no-color.org convention (picocolors/chalk/vite/npm honor it),
        // FORCE_COLOR=0 covers tools that only read FORCE_COLOR. AnsiEscapes.strip at the decode
        // seam catches the tools that ignore both.
        String sandboxTemp =
                profile.workspaceRoot()
                        .resolve(SANDBOX_TEMP_DIRECTORY)
                        .toAbsolutePath()
                        .normalize()
                        .toString();
        environment.put("NO_COLOR", "1");
        environment.put("FORCE_COLOR", "0");
        environment.put("TMPDIR", sandboxTemp);
        environment.put("TEMP", sandboxTemp);
        environment.put("TMP", sandboxTemp);
    }

    private record PreparedProcess(
            @NonNull ProcessBuilder builder,
            KernelSandboxSubstrate.PreparedCommand kernelPreparation)
            implements AutoCloseable {

        @Override
        public void close() {
            if (kernelPreparation != null) {
                kernelPreparation.close();
            }
        }
    }

    /**
     * Resolves a direct executable with the same host environment a normal terminal receives.
     * Windows searches the working directory and PATH using PATHEXT; Unix searches PATH for an
     * executable file. No PowerShell, Bash, or other command interpreter is started. Unresolved
     * names pass through so process creation returns the native not-found error. Screening still
     * classifies the original name rather than this host-resolved path.
     */
    static @NonNull String resolveExecutable(@NonNull String executable, @NonNull Path workdir) {
        if (executable.indexOf('/') >= 0 || executable.indexOf('\\') >= 0) {
            Path supplied = Path.of(executable);
            return (supplied.isAbsolute() ? supplied : workdir.resolve(supplied))
                    .toAbsolutePath()
                    .normalize()
                    .toString();
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return executable;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> extensions;
        if (windows) {
            String pathext = System.getenv("PATHEXT");
            extensions =
                    (pathext == null || pathext.isBlank())
                            ? List.of(".com", ".exe", ".bat", ".cmd")
                            : java.util.Arrays.stream(pathext.split(";"))
                                    .map(String::trim)
                                    .filter(e -> !e.isEmpty())
                                    .map(e -> e.toLowerCase(java.util.Locale.ROOT))
                                    .toList();
        } else {
            extensions = List.of("");
        }
        List<Path> searchDirectories = new ArrayList<>();
        if (windows) {
            searchDirectories.add(workdir);
        }
        for (String dir : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (dir.isBlank()) {
                continue;
            }
            searchDirectories.add(Path.of(dir));
        }
        String lower = executable.toLowerCase(java.util.Locale.ROOT);
        boolean hasExecutableExtension = windows && extensions.stream().anyMatch(lower::endsWith);
        for (Path dir : searchDirectories) {
            if (hasExecutableExtension) {
                Path candidate = dir.resolve(executable);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().normalize().toString();
                }
                continue;
            }
            for (String ext : extensions) {
                Path candidate = dir.resolve(executable + ext);
                if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                    return candidate.toAbsolutePath().normalize().toString();
                }
            }
        }
        return executable;
    }

    /** Windows command shims require ComSpec; ordinary executables never use an interpreter. */
    private static boolean isWindowsCommandShim(@NonNull String executable) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return false;
        }
        String lower = executable.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".cmd") || lower.endsWith(".bat");
    }

    /** Reject tokens that would escape their argv position when ComSpec interprets a shim call. */
    static void validateWindowsCommandShimToken(@NonNull String token) {
        if (token.chars()
                .anyMatch(
                        value ->
                                value == '&'
                                        || value == '|'
                                        || value == '<'
                                        || value == '>'
                                        || value == '^'
                                        || value == '%'
                                        || value == '!'
                                        || value == '"'
                                        || value == '\r'
                                        || value == '\n')) {
            throw new SecurityException(
                    "Windows .cmd/.bat arguments cannot contain command-interpreter metacharacters");
        }
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
            @NonNull List<PreparedProcess> builders,
            @NonNull Duration timeout,
            @NonNull SandboxProfile profile)
            throws IOException, InterruptedException {
        List<ProcessBuilder> processBuilders =
                builders.stream().map(PreparedProcess::builder).toList();
        List<Process> pipeline = ProcessBuilder.startPipeline(processBuilders);
        for (int i = 0; i < pipeline.size(); i++) {
            try {
                establishKernelWall(pipeline.get(i), builders.get(i), profile);
            } catch (IOException e) {
                pipeline.forEach(Process::destroyForcibly);
                throw e;
            }
        }
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
            @NonNull List<PreparedProcess> builders,
            @NonNull ChainMode connect,
            @NonNull Duration timeout,
            @NonNull SandboxProfile profile)
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
        for (PreparedProcess prepared : builders) {
            Duration remaining =
                    capped ? Duration.ofNanos(deadlineNanos - System.nanoTime()) : timeout;
            if (capped && remaining.isNegative()) {
                codes.add(-1);
                return timeoutResult(stdout, stderr, codes);
            }
            Process p = prepared.builder().start();
            establishKernelWall(p, prepared, profile);
            CappedWait wait = waitCapped(p, remaining);
            stdout.append(wait.stdout());
            stderr.append(wait.stderr());
            if (!wait.finished()) {
                codes.add(-1);
                return timeoutResult(stdout, stderr, codes);
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

    private static @NonNull CommandResult timeoutResult(
            @NonNull StringBuilder stdout,
            @NonNull StringBuilder stderr,
            @NonNull List<Integer> codes) {
        return new CommandResult(-1, stdout.toString(), stderr + "\n[timeout]", codes);
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
        BoundedByteBuffer out = new BoundedByteBuffer(MAX_CAPTURE_BYTES_PER_STREAM);
        BoundedByteBuffer err = new BoundedByteBuffer(MAX_CAPTURE_BYTES_PER_STREAM);
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
        String stdout = AnsiEscapes.strip(SubprocessOutput.decode(out.toByteArray()));
        String stderr = AnsiEscapes.strip(SubprocessOutput.decode(err.toByteArray()));
        if (out.truncated()) {
            stdout += "\n[stdout truncated at " + MAX_CAPTURE_BYTES_PER_STREAM + " bytes]";
        }
        if (err.truncated()) {
            stderr += "\n[stderr truncated at " + MAX_CAPTURE_BYTES_PER_STREAM + " bytes]";
        }
        return new CappedWait(finished, stdout, stderr);
    }

    /** One writer (the drain thread) per buffer; the main thread reads only after join(). */
    private static void drain(java.io.@NonNull InputStream in, @NonNull BoundedByteBuffer sink) {
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

    /** Attach the trusted bootstrap, then release it to start the untrusted target. */
    private void establishKernelWall(
            @NonNull Process process,
            @NonNull PreparedProcess prepared,
            @NonNull SandboxProfile profile)
            throws IOException {
        KernelSandboxSubstrate.PreparedCommand preparation = prepared.kernelPreparation();
        if (kernelWall == null || preparation == null) {
            return;
        }
        try {
            preparation.awaitReady(process);
            kernelWall.attach(process, profile);
            preparation.release();
        } catch (RuntimeException e) {
            preparation.close();
            process.destroyForcibly();
            throw new IOException(
                    "Kernel sandbox wall could not be established: " + safe(e.getMessage()), e);
        }
    }

    @Override
    public void deprovision(@NonNull SandboxHandle h) {
        if (kernelWall != null) {
            kernelWall.deprovisionWorkspace(h.profile());
        }
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
}
