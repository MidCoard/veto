package top.focess.veto.agent.screening;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The PROTECT_SENSITIVE protected set — paths default-blocked (CRITICAL on access). Seeded with
 * deployer defaults: ~/.veto/, ~/.ssh, ~/.aws, ~/.gnupg. User-editable. covers() is canonical
 * prefix match. Under FULL the set is empty (no effect).
 */
public record ProtectedSet(Set<Path> paths) {

    public ProtectedSet {
        paths = paths == null ? Set.of() : canonicalizeAll(paths);
    }

    public static ProtectedSet empty() {
        return new ProtectedSet(Set.of());
    }

    /** Seeded with deployer defaults: ~/.veto, ~/.ssh, ~/.aws, ~/.gnupg. */
    public static ProtectedSet withDeployerDefaults() {
        String home = System.getProperty("user.home", "");
        Set<Path> defaults =
                Set.of(
                        Path.of(home, ".veto"),
                        Path.of(home, ".ssh"),
                        Path.of(home, ".aws"),
                        Path.of(home, ".gnupg"));
        return new ProtectedSet(defaults);
    }

    /** True if the canonical path is under any protected entry. */
    public boolean covers(Path canonical) {
        Path c = canonical.toAbsolutePath().normalize();
        for (Path entry : paths) {
            if (c.startsWith(entry)) {
                return true;
            }
        }
        return false;
    }

    private static Set<Path> canonicalizeAll(Set<Path> in) {
        return in.stream().map(p -> p.toAbsolutePath().normalize()).collect(Collectors.toSet());
    }
}
