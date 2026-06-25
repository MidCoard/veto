package top.focess.veto.agent.workspace;

import java.util.List;

/** A multi-root workspace entity (Part 10.1). */
public record Workspace(List<WorkspaceRoot> roots, PathMode pathMode, int currentRootIndex) {

    public Workspace {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalArgumentException("workspace must have >= 1 root");
        }
        if (currentRootIndex < 0 || currentRootIndex >= roots.size()) {
            throw new IllegalArgumentException("currentRootIndex out of range");
        }
    }

    public PathResolver pathResolver() {
        return new PathResolver(roots, pathMode, currentRootIndex);
    }

    /** The N=1 case (today's single-root usage). */
    public static Workspace single(java.nio.file.Path hostPath, PathMode mode) {
        return new Workspace(List.of(WorkspaceRoot.probe(hostPath, TrustMarker.OWNED)), mode, 0);
    }
}
