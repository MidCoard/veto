package top.focess.veto.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import org.jspecify.annotations.NonNull;

/**
 * Substrate configuration for a session — the workspace root + resource caps. This is the Sandbox's
 * own profile (the hard-backstop floor); it is <b>not</b> the Gateway's {@code PolicyProfile} (soft
 * policy; the Sandbox performs no policy).
 *
 * @param workspaceRoot the canonical root all relative tool paths resolve under
 * @param maxMemoryMb memory cap (best-effort on the subprocess substrate)
 * @param maxCpuPercent CPU cap (best-effort)
 * @param maxWallClock the default per-chain wall-clock timeout
 */
public record SandboxProfile(
        @NonNull Path workspaceRoot,
        long maxMemoryMb,
        int maxCpuPercent,
        @NonNull Duration maxWallClock) {

    /** A permissive default profile for the local substrate. */
    public static @NonNull SandboxProfile defaults(@NonNull Path workspaceRoot) {
        return new SandboxProfile(workspaceRoot, 512, 100, Duration.ofMinutes(10));
    }
}
