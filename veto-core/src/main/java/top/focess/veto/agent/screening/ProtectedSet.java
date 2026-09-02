package top.focess.veto.agent.screening;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;

/**
 * The PROTECTED protected set — paths default-blocked (CRITICAL on access). Seeded with deployer
 * defaults: ~/.veto/users/{veto_user_id}/, ~/.ssh, ~/.aws, ~/.gnupg and each workspace root's
 * {@code .env}. The immutable value is resolved from deployer configuration for each user and
 * workspace. {@link #covers} uses canonical prefix matching. Under FULL_ACCESS the set is empty.
 */
public record ProtectedSet(@NonNull Set<Path> paths) {

    public ProtectedSet {
        paths = canonicalizeAll(paths);
    }

    public static @NonNull ProtectedSet empty() {
        return new ProtectedSet(Set.of());
    }

    /**
     * Creates the deployer defaults: {@code ~/.veto/users/{vetoUserId}/}, {@code ~/.ssh}, {@code
     * ~/.aws}, {@code ~/.gnupg}, plus {@code <root>/.env} for each workspace root.
     */
    public static @NonNull ProtectedSet withDeployerDefaults(
            @NonNull String vetoUserId, @NonNull List<Path> workspaceRoots) {
        String home = System.getProperty("user.home", "");
        Set<Path> defaults = new HashSet<>();
        defaults.add(Path.of(home, ".veto", "users", vetoUserId));
        defaults.add(Path.of(home, ".ssh"));
        defaults.add(Path.of(home, ".aws"));
        defaults.add(Path.of(home, ".gnupg"));
        for (Path root : workspaceRoots) {
            if (root != null) {
                defaults.add(root.resolve(".env"));
            }
        }
        return new ProtectedSet(defaults);
    }

    /**
     * The standard system-protected paths relative to the application launch directory: {@code
     * application.yml} / {@code application.yaml} / {@code application.properties}, the {@code
     * config/} directory, and the {@code audit/} directory. These hold deployer config and audit
     * logs the agent must never read. Listed as specific subpaths (not the whole launch dir) so the
     * agent's workspace - if nested under the launch dir - is unaffected.
     */
    public static @NonNull List<Path> standardSystemProtected(@NonNull Path launchDir) {
        List<Path> paths = new ArrayList<>();
        paths.add(launchDir.resolve("application.yml"));
        paths.add(launchDir.resolve("application.yaml"));
        paths.add(launchDir.resolve("application.properties"));
        paths.add(launchDir.resolve("config"));
        paths.add(launchDir.resolve("audit"));
        return paths;
    }

    /** True if the canonical path is under any protected entry. */
    public boolean covers(@NonNull Path canonical) {
        Path c = canonical.toAbsolutePath().normalize();
        for (Path entry : paths) {
            if (c.startsWith(entry)) {
                return true;
            }
        }
        return false;
    }

    private static @NonNull Set<Path> canonicalizeAll(@NonNull Set<Path> in) {
        return in.stream()
                .map(p -> p.toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
