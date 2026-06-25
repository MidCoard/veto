package top.focess.veto.agent.workspace;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A multi-root workspace entity (Part 10.1). */
public record Workspace(List<WorkspaceRoot> roots, PathMode pathMode, int currentRootIndex) {

    private static final Logger log = LoggerFactory.getLogger(Workspace.class);

    public Workspace {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalArgumentException("workspace must have >= 1 root");
        }
        if (currentRootIndex < 0 || currentRootIndex >= roots.size()) {
            throw new IllegalArgumentException("currentRootIndex out of range");
        }
        warnOnRootNameCollisions(roots);
    }

    /**
     * LLD §3: two roots sharing the same directory name collide in VIRTUAL mode — their virtual
     * prefixes ({@code /{rootDirName}}) are identical, so the first-in-order root wins and the
     * other is silently unreachable via virtual path. Warn at assembly time so the deployer
     * notices; first-in-order wins is enforced by {@link PathResolver}, which matches the first
     * segment only.
     */
    private static void warnOnRootNameCollisions(List<WorkspaceRoot> roots) {
        Set<String> seen = new HashSet<>();
        for (WorkspaceRoot root : roots) {
            Path fileName = root.hostPath().getFileName();
            String name = fileName == null ? "" : fileName.toString();
            if (!name.isEmpty() && !seen.add(name)) {
                log.warn(
                        "Workspace root-name collision: multiple roots share the directory name"
                                + " \"{}\". In VIRTUAL mode the first-in-order root wins and the"
                                + " other(s) are unreachable via the virtual path prefix.",
                        name);
            }
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
