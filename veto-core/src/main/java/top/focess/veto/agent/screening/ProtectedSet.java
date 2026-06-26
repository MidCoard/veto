package top.focess.veto.agent.screening;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The PROTECTED protected set — paths default-blocked (CRITICAL on access). Seeded with deployer
 * defaults: ~/.veto/users/{veto_user_id}/, ~/.ssh, ~/.aws, ~/.gnupg and each workspace root's
 * {@code .env} (spec §6). User-editable. covers() is canonical prefix match. Under FULL_ACCESS the
 * set is empty.
 */
public record ProtectedSet(Set<Path> paths) {

    public ProtectedSet {
        paths = paths == null ? Set.of() : canonicalizeAll(paths);
    }

    public static ProtectedSet empty() {
        return new ProtectedSet(Set.of());
    }

    /**
     * Seeded with the home-relative deployer defaults only ({@code ~/.veto/users/default/}, {@code
     * ~/.ssh}, {@code ~/.aws}, {@code ~/.gnupg}).
     */
    public static ProtectedSet withDeployerDefaults() {
        return withDeployerDefaults("default", List.of());
    }

    public static ProtectedSet withDeployerDefaults(List<Path> workspaceRoots) {
        return withDeployerDefaults("default", workspaceRoots);
    }

    /**
     * Seeded with deployer defaults per spec §6: {@code ~/.veto/users/{vetoUserId}/}, {@code
     * ~/.ssh}, {@code ~/.aws}, {@code ~/.gnupg}, plus {@code <root>/.env} for each workspace root.
     */
    public static ProtectedSet withDeployerDefaults(String vetoUserId, List<Path> workspaceRoots) {
        String home = System.getProperty("user.home", "");
        Set<Path> defaults = new HashSet<>();
        defaults.add(Path.of(home, ".veto", "users", vetoUserId));
        defaults.add(Path.of(home, ".ssh"));
        defaults.add(Path.of(home, ".aws"));
        defaults.add(Path.of(home, ".gnupg"));
        if (workspaceRoots != null) {
            for (Path root : workspaceRoots) {
                if (root != null) {
                    defaults.add(root.resolve(".env"));
                }
            }
        }
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
