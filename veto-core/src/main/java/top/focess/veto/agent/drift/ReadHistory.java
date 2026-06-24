package top.focess.veto.agent.drift;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
    public record Snapshot(String path, long fileSize, Instant lastModified, String sha256Hash) {}

    /** Called by the sandbox wrapper after every file read. */
    public void record(String path, long size, Instant mtime, String hash) {
        history.put(path, new Snapshot(path, size, mtime, hash));
    }

    /** Called by the Gateway before every file write. */
    public Optional<Snapshot> lookup(String path) {
        return Optional.ofNullable(history.get(path));
    }

    /** Called when the agent successfully writes — the recorded snapshot is now stale. */
    public void invalidate(String path) {
        history.remove(path);
    }

    /** Number of recorded reads (for diagnostics / escape messages). */
    public int size() {
        return history.size();
    }

    /** Resolves a workspace-relative path string against the agent's virtual root. */
    public static Path resolveHostPath(Path workspaceRoot, String relativePath) {
        return workspaceRoot.resolve(relativePath).normalize();
    }
}
