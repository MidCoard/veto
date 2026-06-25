package top.focess.veto.agent.workspace;

import java.nio.file.Path;
import java.util.Arrays;
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

    public VetoMdResolver vetoMdResolver() {
        return new VetoMdResolver(roots);
    }

    /** The N=1 case (today's single-root usage). */
    public static Workspace single(Path hostPath, PathMode mode) {
        return new Workspace(List.of(WorkspaceRoot.probe(hostPath, TrustMarker.OWNED)), mode, 0);
    }

    /**
     * Builds a {@link Workspace} from deployer config: a multi-root workspace when {@code rootsCsv}
     * (CSV) is non-blank, otherwise a single root at {@code legacyRoot} (defaulting to the JVM
     * working dir). {@code pathMode} selects VIRTUAL vs REAL ("VIRTUAL" → VIRTUAL, anything else →
     * REAL).
     */
    public static Workspace fromConfig(String legacyRoot, String rootsCsv, String pathMode) {
        PathMode mode = "VIRTUAL".equalsIgnoreCase(pathMode) ? PathMode.VIRTUAL : PathMode.REAL;
        if (rootsCsv != null && !rootsCsv.isBlank()) {
            List<WorkspaceRoot> roots =
                    Arrays.stream(rootsCsv.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .map(p -> WorkspaceRoot.probe(Path.of(p), TrustMarker.OWNED))
                            .toList();
            return new Workspace(roots, mode, 0);
        }
        Path single =
                (legacyRoot == null || legacyRoot.isBlank())
                        ? Path.of(System.getProperty("user.dir", "."))
                        : Path.of(legacyRoot);
        return Workspace.single(single, mode);
    }
}
