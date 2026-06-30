package top.focess.veto.agent.drift;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.workspace.Workspace;

/**
 * Records the state of every file the agent reads during a session; the {@link
 * top.focess.veto.agent.intercept.Gateway} consults it on every write to detect external
 * modifications. Lazy, demand-driven: record on read, compare at write, invalidate after a
 * successful write. No snapshot-on-pause, no scan-on-resume.
 *
 * <p>Per-agent / per-session. Keyed by the workspace-relative path (the same form the read and
 * write tools use), so the agent's virtual-root resolution is applied before lookup.
 */
public class ReadHistory {

    private final ConcurrentHashMap<String, Snapshot> history = new ConcurrentHashMap<>();

    /** A recorded file state at read time. */
    public record Snapshot(
            @NonNull String path,
            long fileSize,
            @NonNull Instant lastModified,
            @NonNull String sha256Hash) {}

    /** Called by the sandbox wrapper after every file read. */
    public void record(
            @NonNull String path, long size, @NonNull Instant mtime, @NonNull String hash) {
        history.put(path, new Snapshot(path, size, mtime, hash));
    }

    /** Called by the Gateway before every file write. */
    public @NonNull Optional<Snapshot> lookup(@NonNull String path) {
        return Optional.ofNullable(history.get(path));
    }

    /** Called when the agent successfully writes — the recorded snapshot is now stale. */
    public void invalidate(@NonNull String path) {
        history.remove(path);
    }

    /** Number of recorded reads (for diagnostics / escape messages). */
    public int size() {
        return history.size();
    }

    /** Resolves an agent path string against the workspace's path resolver. */
    public static @NonNull Path resolveHostPath(
            @NonNull Workspace workspace, @NonNull String agentPath) {
        return workspace.pathResolver().resolveToHost(agentPath).hostPath();
    }
}
