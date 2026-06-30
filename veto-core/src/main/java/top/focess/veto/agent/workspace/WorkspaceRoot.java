package top.focess.veto.agent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One root of a workspace — a directory (typically a Git repo) on the host. Carries its absolute
 * path, whether it's a Git repo (+ current branch if so), and its trust marker.
 */
public record WorkspaceRoot(
        Path hostPath, boolean isGitRepo, String currentBranch, TrustMarker trust) {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceRoot.class);

    /** Probes a host path: is it a git repo, what branch, with the given trust. */
    public static @NonNull WorkspaceRoot probe(@NonNull Path hostPath, @NonNull TrustMarker trust) {
        Path normalized = hostPath.toAbsolutePath().normalize();
        boolean isGit = Files.isDirectory(normalized.resolve(".git"));
        String branch = isGit ? readBranch(normalized) : null;
        return new WorkspaceRoot(normalized, isGit, branch, trust);
    }

    private static String readBranch(Path gitRoot) {
        ProcessBuilder pb =
                new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                        .directory(gitRoot.toFile());
        try {
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            int exit = p.waitFor();
            return exit == 0 && !out.isEmpty() ? out : null;
        } catch (IOException | InterruptedException e) {
            log.warn("Could not read git branch for {}", gitRoot, e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }
}
