package top.focess.veto.agent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves agent paths to host paths (VIRTUAL: virtual-under-`/` by root prefix; REAL: canonicalize
 * + find containing root) and identifies which root (or out-of-scope). Does NOT classify danger —
 * that's Part 3 (sub-spec B). This only answers "which root, or out-of-scope."
 *
 * <p><b>Traversal safety (LLD §3):</b> after resolving a host path lexically, it is canonicalized
 * and then re-classified against the roots. Canonicalization resolves symlinks in the <i>existing
 * prefix</i> of the path (the common write/create case targets a non-existent file through a
 * possibly-symlinked directory): {@link Path#toRealPath()} the path itself when it exists, else
 * walk up to the deepest existing ancestor, {@code toRealPath()} <i>that</i> (resolving outward
 * symlinks the whole-path call could not), and re-append the non-existent tail. A {@code ..} or
 * symlink chain that escapes its root thus resolves to a host outside the roots and is reported
 * {@code outOfScope} — the real path's class decides, no special-casing.
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
     * Canonicalizes a host path: resolves {@code ..} and symlinks to their real target so an
     * outward escape is visible to the {@code startsWith(root)} check. This is the LLD §3 "resolve
     * → classify against roots" canonicalization step applied uniformly in VIRTUAL and REAL modes.
     *
     * <p>The subtlety is the common write/create case: the agent targets a <i>non-existent</i> path
     * that runs <i>through</i> an existing outward symlink (e.g. {@code /root/escape-link/newfile}
     * where {@code escape-link} → an outside directory and {@code newfile} is absent). A naive
     * {@link Path#toRealPath()} of the whole path throws {@link IOException} because the final
     * component is absent, and a pure-lexical fallback ({@code toAbsolutePath().normalize()})
     * cannot resolve the symlink — leaving it invisible, so {@code startsWith(root)} is true and
     * the path is falsely classified in-scope. A subsequent write then lands outside all roots.
     *
     * <p>To close that, canonicalize the <i>existing prefix</i> component-by-component: (1) if the
     * path itself exists, {@code toRealPath()} resolves everything in one shot; (2) otherwise walk
     * up to the deepest existing ancestor, {@code toRealPath()} <i>that</i> (which succeeds and
     * resolves any symlink in the existing prefix), then re-append the non-existent tail and {@code
     * normalize()}; (3) if {@code toRealPath()} of the ancestor still throws, fall back to a
     * lexical canonicalization of ancestor + tail — safe because a totally-non-existent prefix has
     * no symlink to exploit.
     */
    private Path canonicalize(Path host) {
        // (1) Fast path: the path itself exists → toRealPath() resolves all symlinks and '..' in
        // one shot (also catches '..' escapes that normalize() would keep lexically under a root).
        if (Files.exists(host)) {
            try {
                return host.toRealPath();
            } catch (IOException e) {
                return host.toAbsolutePath().normalize();
            }
        }
        // (2) Non-existent target (write/create case): walk up to the deepest EXISTING ancestor and
        // canonicalize IT, so an outward symlink in the existing prefix (e.g. escape-link →
        // outside)
        // is resolved before the tail is re-appended.
        Path absolute = host.toAbsolutePath();
        Path existingAncestor = absolute;
        while (existingAncestor != null && !Files.exists(existingAncestor)) {
            existingAncestor = existingAncestor.getParent();
        }
        if (existingAncestor == null) {
            // Entirely non-existent prefix (no ancestor exists, not even the FS root): no symlink
            // to
            // exploit, so a purely lexical canonicalization is safe.
            return absolute.normalize();
        }
        Path canonicalAncestor;
        try {
            canonicalAncestor = existingAncestor.toRealPath();
        } catch (IOException e) {
            // (3) Ancestor exists per Files.exists but the FS can't fully resolve it (race /
            // permissions): lexical fallback of ancestor + tail is the best we can do.
            return absolute.normalize();
        }
        Path tail = existingAncestor.relativize(absolute);
        return canonicalAncestor.resolve(tail).normalize();
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
