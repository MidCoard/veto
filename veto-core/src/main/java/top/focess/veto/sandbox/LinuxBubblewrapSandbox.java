package top.focess.veto.sandbox;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/** Codex-aligned Linux launcher: Bubblewrap filesystem/namespaces plus an inner seccomp stage. */
final class LinuxBubblewrapSandbox {

    private static final @NonNull List<@NonNull Path> SYSTEM_BWRAP_CANDIDATES =
            List.of(Path.of("/usr/bin/bwrap"), Path.of("/bin/bwrap"));
    private static final @NonNull List<@NonNull String> PROTECTED_METADATA_NAMES =
            List.of(".git", ".agents", ".codex");
    private final Path configuredBubblewrap;

    LinuxBubblewrapSandbox() {
        configuredBubblewrap = null;
    }

    LinuxBubblewrapSandbox(@NonNull Path configuredBubblewrap) {
        this.configuredBubblewrap = configuredBubblewrap.toAbsolutePath().normalize();
    }

    boolean isAvailable() {
        return findBubblewrap(Path.of("").toAbsolutePath().normalize()) != null;
    }

    @NonNull List<@NonNull String> wrap(
            @NonNull List<@NonNull String> targetCommand,
            @NonNull SandboxProfile profile,
            @NonNull Path cwd) {
        if (targetCommand.isEmpty()) {
            throw new IllegalArgumentException("targetCommand must not be empty");
        }
        Path workspace = realDirectory(profile.workspaceRoot(), "workspace");
        Path workdir = realDirectory(cwd, "working directory");
        if (!workdir.startsWith(workspace)) {
            throw new SecurityException("Linux sandbox cwd escapes workspace: " + cwd);
        }
        Path bubblewrap = findBubblewrap(workspace);
        if (bubblewrap == null) {
            throw new IllegalStateException(
                    "Bubblewrap is unavailable outside the workspace; refusing an unsandboxed Linux process");
        }

        List<String> command = new ArrayList<>();
        command.add(bubblewrap.toString());
        command.add("--die-with-parent");
        command.add("--new-session");
        command.add("--unshare-user");
        command.add("--unshare-pid");
        command.add("--unshare-ipc");
        command.add("--unshare-net");
        command.add("--ro-bind");
        command.add("/");
        command.add("/");
        command.add("--dev");
        command.add("/dev");
        command.add("--bind-try");
        command.add("/dev/shm");
        command.add("/dev/shm");
        command.add("--proc");
        command.add("/proc");
        command.add("--tmpfs");
        command.add("/tmp");
        command.add("--bind");
        command.add(workspace.toString());
        command.add(workspace.toString());
        appendProtectedMetadataMounts(command, workspace);
        command.add("--chdir");
        command.add(workdir.toString());
        command.add("--cap-drop");
        command.add("ALL");
        command.add("--");
        command.addAll(innerBootstrapInvocation());
        command.add(SandboxBootstrap.LINUX_CHILD_MARKER);
        command.add("--");
        command.addAll(targetCommand);
        return List.copyOf(command);
    }

    private static void appendProtectedMetadataMounts(
            @NonNull List<@NonNull String> command, @NonNull Path workspace) {
        for (String name : PROTECTED_METADATA_NAMES) {
            Path protectedPath = workspace.resolve(name);
            if (!Files.exists(protectedPath)) {
                continue;
            }
            Path real;
            try {
                real = protectedPath.toRealPath();
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Protected Linux sandbox path cannot be resolved: " + protectedPath, e);
            }
            if (!real.startsWith(workspace)) {
                throw new SecurityException(
                        "Protected metadata path resolves outside workspace: " + protectedPath);
            }
            command.add("--ro-bind");
            command.add(real.toString());
            command.add(real.toString());
        }
    }

    private static @NonNull List<@NonNull String> innerBootstrapInvocation() {
        List<String> command = new ArrayList<>();
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            command.add(
                    ProcessHandle.current()
                            .info()
                            .command()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Native Veto executable path is unavailable")));
            return command;
        }
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
        String classPath = System.getProperty("java.class.path", "");
        if (classPath.isBlank()) {
            throw new IllegalStateException(
                    "Java classpath is unavailable for Linux sandbox bootstrap");
        }
        classPath = SandboxBootstrap.absoluteClassPath(classPath);
        command.add(javaExecutable.toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Xms8m");
        command.add("-Xmx64m");
        command.add("-XX:MaxMetaspaceSize=64m");
        command.add("-XX:ReservedCodeCacheSize=32m");
        command.add("-XX:+UseSerialGC");
        if (!classPath.contains(File.pathSeparator) && classPath.endsWith(".jar")) {
            command.add("-Dloader.main=" + SandboxBootstrap.class.getName());
            command.add("-cp");
            command.add(classPath);
            command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
        } else {
            command.add("-cp");
            command.add(classPath);
            command.add(SandboxBootstrap.class.getName());
        }
        return command;
    }

    private Path findBubblewrap(@NonNull Path excludedRoot) {
        Path configured = configuredBubblewrap;
        if (configured != null) {
            return usableCandidate(configured, excludedRoot);
        }
        Set<Path> candidates = new LinkedHashSet<>(SYSTEM_BWRAP_CANDIDATES);
        String path = System.getenv("PATH");
        if (path != null && !path.isBlank()) {
            for (String entry : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    candidates.add(Path.of(entry).toAbsolutePath().normalize().resolve("bwrap"));
                }
            }
        }
        for (Path candidate : candidates) {
            Path usable = usableCandidate(candidate, excludedRoot);
            if (usable != null) {
                return usable;
            }
        }
        return null;
    }

    private static Path usableCandidate(@NonNull Path candidate, @NonNull Path excludedRoot) {
        if (!Files.isRegularFile(candidate) || !Files.isExecutable(candidate)) {
            return null;
        }
        try {
            Path real = candidate.toRealPath();
            return real.startsWith(excludedRoot) ? null : real;
        } catch (IOException ignored) {
            // A disappearing or unreadable launcher is not a usable security boundary.
            return null;
        }
    }

    private static @NonNull Path realDirectory(@NonNull Path path, @NonNull String label) {
        try {
            Path real = path.toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(real)) {
                throw new IllegalStateException(
                        "Linux sandbox " + label + " is not a directory: " + path);
            }
            return real;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Linux sandbox " + label + " cannot be resolved: " + path, e);
        }
    }
}
