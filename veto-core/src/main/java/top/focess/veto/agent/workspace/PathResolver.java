package top.focess.veto.agent.workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves agent paths to host paths (VIRTUAL: virtual-under-`/` by root prefix; REAL: canonicalize
 * + find containing root) and identifies which root (or out-of-scope). Does NOT classify danger —
 * that's Part 3 (sub-spec B). This only answers "which root, or out-of-scope."
 *
 * <p><b>Traversal safety (LLD §3):</b> after resolving a host path lexically, it is canonicalized
 * (best-effort {@link Path#toRealPath()}, falling back to {@code toAbsolutePath().normalize()} when
 * the file does not exist or the FS can't resolve it) and then re-classified against the roots. A
 * {@code ..} or symlink chain that escapes its root resolves to a host outside the roots and is
 * reported {@code outOfScope} — the real path's class decides, no special-casing.
 */
public class PathResolver {

    private final List<WorkspaceRoot> roots;
    private final PathMode pathMode;
    private final int currentRootIndex;

    public PathResolver(List<WorkspaceRoot> roots, PathMode pathMode, int currentRootIndex) {
        this.roots = roots;
        this.pathMode = pathMode;
        this.currentRootIndex = currentRootIndex;
    }

    /** Resolves an agent path to a host path + which root, or out-of-scope. */
    public Resolution resolveToHost(String agentPath) {
        if (agentPath == null || agentPath.isBlank()) {
            return Resolution.outOfScope(null);
        }
        if (pathMode == PathMode.VIRTUAL) {
            return resolveVirtual(agentPath);
        }
        return resolveReal(agentPath);
    }

    /** The current operational root's host path (for relative-path resolution). */
    public Path operationalRoot() {
        return roots.get(currentRootIndex).hostPath();
    }

    private Resolution resolveVirtual(String agentPath) {
        if (agentPath.startsWith("/")) {
            // absolute virtual: /{rootDirName}/...
            int slash = agentPath.indexOf('/', 1);
            String firstSegment =
                    slash > 0 ? agentPath.substring(1, slash) : agentPath.substring(1);
            for (int i = 0; i < roots.size(); i++) {
                String name = rootName(i);
                if (name.equals(firstSegment)) {
                    String remainder = slash > 0 ? agentPath.substring(slash + 1) : "";
                    Path host = roots.get(i).hostPath().resolve(remainder).normalize();
                    // Canonicalize (.. + symlink traversal) and re-classify against the matched
                    // root: a virtual path that traverses out of its own root escapes the
                    // workspace.
                    return classifyAgainstRoot(canonicalize(host), i);
                }
            }
            return Resolution.outOfScope(null);
        }
        // relative → operational root
        Path host = operationalRoot().resolve(agentPath).normalize();
        return classifyAgainstRoot(canonicalize(host), currentRootIndex);
    }

    private Resolution resolveReal(String agentPath) {
        Path candidate = canonicalize(Path.of(agentPath).toAbsolutePath().normalize());
        for (int i = 0; i < roots.size(); i++) {
            Path rootHost = roots.get(i).hostPath();
            if (candidate.startsWith(rootHost)) {
                return new Resolution(candidate, i, true);
            }
        }
        return Resolution.outOfScope(candidate);
    }

    /**
     * Canonicalizes a host path: best-effort {@link Path#toRealPath()} (resolves {@code ..} and
     * symlinks to their real target), falling back to {@code toAbsolutePath().normalize()} if the
     * file does not exist or the FS can't resolve it. This is the LLD §3 "resolve → classify
     * against roots" canonicalization step applied uniformly in VIRTUAL and REAL modes.
     */
    private Path canonicalize(Path host) {
        try {
            return host.toRealPath();
        } catch (IOException e) {
            return host.toAbsolutePath().normalize();
        }
    }

    /**
     * Re-classifies an already-canonicalized host path against the matched root {@code i}
     * (VIRTUAL). If it still lands under {@code roots[i]} after canonicalization it is in-scope;
     * otherwise the canonical path traversed out of the workspace → out-of-scope (LLD §3: the real
     * path's class decides, no special-casing).
     */
    private Resolution classifyAgainstRoot(Path canonical, int i) {
        if (canonical.startsWith(roots.get(i).hostPath())) {
            return new Resolution(canonical, i, true);
        }
        return Resolution.outOfScope(canonical);
    }

    private String rootName(int i) {
        Path name = roots.get(i).hostPath().getFileName();
        return name == null ? "" : name.toString();
    }
}
