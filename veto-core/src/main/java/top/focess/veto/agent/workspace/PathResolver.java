package top.focess.veto.agent.workspace;

import java.nio.file.Path;
import java.util.List;

/**
 * Resolves agent paths to host paths (VIRTUAL: virtual-under-`/` by root prefix; REAL: canonicalize
 * + find containing root) and identifies which root (or out-of-scope). Does NOT classify danger —
 * that's Part 3 (sub-spec B). This only answers "which root, or out-of-scope."
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
                    return new Resolution(host, i, true);
                }
            }
            return Resolution.outOfScope(null);
        }
        // relative → operational root
        Path host = operationalRoot().resolve(agentPath).normalize();
        return new Resolution(host, currentRootIndex, true);
    }

    private Resolution resolveReal(String agentPath) {
        Path candidate = Path.of(agentPath).toAbsolutePath().normalize();
        for (int i = 0; i < roots.size(); i++) {
            Path rootHost = roots.get(i).hostPath();
            if (candidate.startsWith(rootHost)) {
                return new Resolution(candidate, i, true);
            }
        }
        return Resolution.outOfScope(candidate);
    }

    private String rootName(int i) {
        Path name = roots.get(i).hostPath().getFileName();
        return name == null ? "" : name.toString();
    }
}
