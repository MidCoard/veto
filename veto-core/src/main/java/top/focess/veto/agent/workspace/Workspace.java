package top.focess.veto.agent.workspace;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A multi-root workspace entity. */
public record Workspace(
        @NonNull List<@NonNull WorkspaceRoot> roots,
        @NonNull PathMode pathMode,
        int currentRootIndex) {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.workspace.Workspace");

    public Workspace {
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("workspace must have >= 1 root");
        }
        if (currentRootIndex < 0 || currentRootIndex >= roots.size()) {
            throw new IllegalArgumentException("currentRootIndex out of range");
        }
        warnOnRootNameCollisions(roots);
    }

    /**
     * Two roots sharing the same directory name collide in VIRTUAL mode — their virtual prefixes
     * ({@code /{rootDirName}}) are identical, so the first-in-order root wins and the other is
     * silently unreachable via virtual path. Warn at assembly time so the deployer notices;
     * first-in-order wins is enforced by {@link PathResolver}, which matches the first segment
     * only.
     */
    private static void warnOnRootNameCollisions(@NonNull List<@NonNull WorkspaceRoot> roots) {
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

    public @NonNull PathResolver pathResolver() {
        return new PathResolver(roots, pathMode, currentRootIndex);
    }

    /**
     * The absolute host paths of each workspace root (canonicalized by {@link
     * WorkspaceRoot#of(Path, TrustMarker)}).
     */
    public @NonNull List<@NonNull Path> hostRoots() {
        return roots.stream().map(WorkspaceRoot::hostPath).toList();
    }

    /** The session-selected root used as the working directory for process tools. */
    public @NonNull Path currentHostRoot() {
        return roots.get(currentRootIndex).hostPath();
    }

    public @NonNull VetoMdResolver vetoMdResolver() {
        return new VetoMdResolver(roots);
    }

    /** The N=1 case (today's single-root usage). */
    public static @NonNull Workspace single(@NonNull Path hostPath, @NonNull PathMode mode) {
        return new Workspace(List.of(WorkspaceRoot.of(hostPath, TrustMarker.OWNED)), mode, 0);
    }

    /**
     * Builds a {@link Workspace} from deployer config: a multi-root workspace when {@code rootsCsv}
     * (CSV) is non-blank, otherwise a single root at {@code legacyRoot} (defaulting to the JVM
     * working dir). {@code pathMode} selects VIRTUAL vs REAL ("VIRTUAL" → VIRTUAL, anything else →
     * REAL).
     */
    public static @NonNull Workspace fromConfig(
            @NonNull String legacyRoot, @NonNull String rootsCsv, @NonNull String pathMode) {
        return fromConfig(legacyRoot, rootsCsv, pathMode, 0);
    }

    public static @NonNull Workspace fromConfig(
            @NonNull String legacyRoot,
            @NonNull String rootsCsv,
            @NonNull String pathMode,
            int currentRootIndex) {
        PathMode mode = "VIRTUAL".equalsIgnoreCase(pathMode) ? PathMode.VIRTUAL : PathMode.REAL;
        if (!rootsCsv.isBlank()) {
            List<@NonNull WorkspaceRoot> roots =
                    Arrays.stream(rootsCsv.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .map(p -> WorkspaceRoot.of(Path.of(p), TrustMarker.OWNED))
                            .toList();
            return new Workspace(roots, mode, currentRootIndex);
        }
        Path single =
                legacyRoot.isBlank()
                        ? Path.of(System.getProperty("user.dir", "."))
                        : Path.of(legacyRoot);
        if (currentRootIndex != 0) {
            throw new IllegalArgumentException("currentRootIndex out of range");
        }
        return Workspace.single(single, mode);
    }
}
