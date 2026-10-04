package top.focess.veto.sandbox;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import top.focess.veto.security.HostPathInput;

/** Codex-aligned Linux launcher: Bubblewrap filesystem/namespaces plus an inner seccomp stage. */
final class LinuxBubblewrapSandbox {

    private static final @NonNull List<@NonNull Path> SYSTEM_BWRAP_CANDIDATES =
            List.of(Path.of("/usr/bin/bwrap"), Path.of("/bin/bwrap"));
    private static final @NonNull List<@NonNull String> PROTECTED_METADATA_NAMES =
            List.of(".agents", ".codex");
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
        appendDeniedMounts(command, profile, workspace, workdir);
        command.add("--chdir");
        command.add(workdir.toString());
        command.add("--cap-drop");
        command.add("ALL");
        command.add("--");
        command.addAll(SandboxBootstrap.processInvocation());
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

    /** Denials are mounted last so broad host/workspace binds cannot expose their contents. */
    private static void appendDeniedMounts(
            @NonNull List<@NonNull String> command,
            @NonNull SandboxProfile profile,
            @NonNull Path workspace,
            @NonNull Path cwd) {
        List<Path> masked = new ArrayList<>();
        for (Path denied :
                profile.deniedPaths().stream()
                        .map(
                                path ->
                                        HostPathInput.canonicalForCreation(
                                                path, "denied sandbox path"))
                        .sorted(Comparator.comparingInt(Path::getNameCount))
                        .toList()) {
            if (cwd.startsWith(denied))
                throw new SecurityException("Sandbox working directory is protected");
            if (masked.stream().anyMatch(denied::startsWith)) continue;
            if (!Files.exists(denied)) {
                Path ancestor = denied.getParent();
                while (ancestor != null && !Files.isDirectory(ancestor)) {
                    ancestor = ancestor.getParent();
                }
                if (ancestor == null
                        || workspace.startsWith(ancestor)
                        || Path.of("/").equals(ancestor)
                        || Path.of("/tmp").startsWith(ancestor)
                        || Path.of("/dev").startsWith(ancestor)
                        || Path.of("/proc").startsWith(ancestor))
                    throw new SecurityException(
                            "Cannot safely mask the ancestor of an absent protected path");
                // Mask an existing ancestor so host-side creation cannot expose a future entry.
                command.add("--tmpfs");
                command.add(ancestor.toString());
                command.add("--remount-ro");
                command.add(ancestor.toString());
                masked.add(ancestor);
                continue;
            }
            if (Files.isDirectory(denied)) {
                command.add("--tmpfs");
                command.add(denied.toString());
                command.add("--remount-ro");
                command.add(denied.toString());
            } else if (Files.isRegularFile(denied)) {
                command.add("--ro-bind");
                command.add("/dev/null");
                command.add(denied.toString());
            } else {
                throw new SecurityException("Cannot safely mask protected special file");
            }
            masked.add(denied);
        }
    }

    private Path findBubblewrap(@NonNull Path excludedRoot) {
        Path configured = configuredBubblewrap;
        if (configured != null) {
            return usableCandidate(configured, excludedRoot);
        }
        Set<Path> candidates = new LinkedHashSet<>(SYSTEM_BWRAP_CANDIDATES);
        String path = System.getenv("PATH");
        if (path != null && !path.isBlank()) {
            for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
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
