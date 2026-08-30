package top.focess.veto.sandbox;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;

/**
 * Substrate configuration for a session — the workspace root + resource caps. This is the Sandbox's
 * own profile (the hard-backstop floor); it is <b>not</b> the Gateway's {@code PolicyProfile} (soft
 * policy; the Sandbox performs no policy).
 *
 * @param workspaceRoot the canonical root all relative tool paths resolve under
 * @param maxMemoryMb aggregate hard memory cap for the sandbox process tree
 * @param maxCpuPercent hard CPU-rate cap for the sandbox process tree
 * @param maxProcesses maximum number of processes in the sandbox process tree
 * @param maxWallClock the default per-chain wall-clock timeout
 * @param networkAllowed whether this exact Gateway-approved execution may use the network
 * @param deniedPaths canonical paths that the already-screened workspace policy requires the OS
 *     boundary to keep unreachable
 * @param readExecuteRoots host paths exposed read/execute for terminal-compatible tool discovery;
 *     this is reachability, never a declaration that code under those roots is trusted
 * @param readWriteExecuteRoots host cache/temp paths exposed read/write/execute
 */
public record SandboxProfile(
        @NonNull Path workspaceRoot,
        long maxMemoryMb,
        int maxCpuPercent,
        int maxProcesses,
        @NonNull Duration maxWallClock,
        boolean networkAllowed,
        @NonNull Set<@NonNull Path> deniedPaths,
        @NonNull Set<@NonNull Path> readExecuteRoots,
        @NonNull Set<@NonNull Path> readWriteExecuteRoots) {

    public SandboxProfile {
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        deniedPaths =
                deniedPaths.stream()
                        .map(path -> path.toAbsolutePath().normalize())
                        .collect(Collectors.toUnmodifiableSet());
        readExecuteRoots = canonicalizeRoots(readExecuteRoots);
        readWriteExecuteRoots = canonicalizeRoots(readWriteExecuteRoots);
        if (maxMemoryMb <= 0) {
            throw new IllegalArgumentException("maxMemoryMb must be positive");
        }
        if (maxCpuPercent <= 0 || maxCpuPercent > 100) {
            throw new IllegalArgumentException("maxCpuPercent must be in [1,100]");
        }
        if (maxProcesses <= 0) {
            throw new IllegalArgumentException("maxProcesses must be positive");
        }
        if (maxWallClock.isZero() || maxWallClock.isNegative()) {
            throw new IllegalArgumentException("maxWallClock must be positive");
        }
    }

    /** Compatibility constructor for profiles without policy-projected deny paths. */
    public SandboxProfile(
            @NonNull Path workspaceRoot,
            long maxMemoryMb,
            int maxCpuPercent,
            int maxProcesses,
            @NonNull Duration maxWallClock) {
        this(
                workspaceRoot,
                maxMemoryMb,
                maxCpuPercent,
                maxProcesses,
                maxWallClock,
                false,
                Set.of(),
                Set.of(),
                Set.of());
    }

    /**
     * Compatibility constructor with policy-projected deny paths but no host compatibility roots.
     */
    public SandboxProfile(
            @NonNull Path workspaceRoot,
            long maxMemoryMb,
            int maxCpuPercent,
            int maxProcesses,
            @NonNull Duration maxWallClock,
            @NonNull Set<@NonNull Path> deniedPaths) {
        this(
                workspaceRoot,
                maxMemoryMb,
                maxCpuPercent,
                maxProcesses,
                maxWallClock,
                false,
                deniedPaths,
                Set.of(),
                Set.of());
    }

    /** A permissive default profile for the local substrate. */
    public static @NonNull SandboxProfile defaults(@NonNull Path workspaceRoot) {
        return new SandboxProfile(workspaceRoot, 512, 100, 64, Duration.ofMinutes(10));
    }

    /**
     * Default resource limits with the Gateway-approved protected paths projected into the wall.
     */
    public static @NonNull SandboxProfile forExecution(
            @NonNull Path workspaceRoot, @NonNull Set<@NonNull Path> deniedPaths) {
        return forExecution(workspaceRoot, deniedPaths, false);
    }

    /** Policy projection for one screened process execution. */
    public static @NonNull SandboxProfile forExecution(
            @NonNull Path workspaceRoot,
            @NonNull Set<@NonNull Path> deniedPaths,
            boolean networkAllowed) {
        EnvironmentRoots environmentRoots = EnvironmentRoots.discover(System.getenv());
        return new SandboxProfile(
                workspaceRoot,
                512,
                100,
                64,
                Duration.ofMinutes(10),
                networkAllowed,
                deniedPaths,
                environmentRoots.readExecute(),
                environmentRoots.readWriteExecute());
    }

    private static @NonNull Set<@NonNull Path> canonicalizeRoots(
            @NonNull Set<@NonNull Path> roots) {
        return roots.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Generic path-valued environment discovery; no runtime names or trusted-program list. */
    record EnvironmentRoots(
            @NonNull Set<@NonNull Path> readExecute, @NonNull Set<@NonNull Path> readWriteExecute) {

        static @NonNull EnvironmentRoots discover(@NonNull Map<String, String> environment) {
            Set<Path> readExecute = new HashSet<>();
            Set<Path> readWriteExecute = new HashSet<>();
            for (var entry : environment.entrySet()) {
                String name = entry.getKey().toUpperCase(java.util.Locale.ROOT);
                boolean cache = name.contains("CACHE");
                boolean executionPath =
                        name.equals("PATH")
                                || name.endsWith("_PATH")
                                || name.endsWith("PATHS")
                                || name.endsWith("_HOME")
                                || cache;
                if (!executionPath) {
                    continue;
                }
                String value = entry.getValue();
                if (value == null || value.isBlank()) {
                    continue;
                }
                for (String component :
                        value.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                    addExistingAbsolutePath(
                            component, readExecute, cache ? readWriteExecute : null);
                }
            }
            return new EnvironmentRoots(Set.copyOf(readExecute), Set.copyOf(readWriteExecute));
        }

        private static void addExistingAbsolutePath(
                @NonNull String value, @NonNull Set<Path> readExecute, Set<Path> readWriteExecute) {
            String candidate = value.strip();
            if (candidate.isEmpty() || candidate.indexOf('%') >= 0) {
                return;
            }
            final Path parsed;
            try {
                parsed = Path.of(candidate);
            } catch (RuntimeException ignored) {
                return;
            }
            if (!parsed.isAbsolute() || !Files.exists(parsed)) {
                return;
            }
            Path root = Files.isDirectory(parsed) ? parsed : parsed.getParent();
            if (root == null) {
                return;
            }
            Path normalized = root.toAbsolutePath().normalize();
            readExecute.add(normalized);
            if (readWriteExecute != null) {
                readWriteExecute.add(normalized);
            }
        }
    }
}
