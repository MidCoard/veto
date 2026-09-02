package top.focess.veto.agent.screening;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;

/**
 * The PROTECTED protected set — paths default-blocked (CRITICAL on access). Seeded with deployer
 * defaults: ~/.veto/users/{veto_user_id}/, ~/.ssh, ~/.aws, ~/.gnupg and each workspace root's
 * {@code .env} (spec §6). The immutable value is resolved from deployer configuration for each
 * user/workspace. covers() is canonical prefix match. Under FULL_ACCESS the set is empty.
 */
public record ProtectedSet(@NonNull Set<Path> paths) {

    public ProtectedSet {
        paths = canonicalizeAll(paths);
    }

    public static @NonNull ProtectedSet empty() {
        return new ProtectedSet(Set.of());
    }

    /**
     * Seeded with the home-relative deployer defaults only ({@code ~/.veto/users/default/}, {@code
     * ~/.ssh}, {@code ~/.aws}, {@code ~/.gnupg}).
     */
    public static @NonNull ProtectedSet withDeployerDefaults() {
        return withDeployerDefaults("default", List.of());
    }

    public static @NonNull ProtectedSet withDeployerDefaults(@NonNull List<Path> workspaceRoots) {
        return withDeployerDefaults("default", workspaceRoots);
    }

    /**
     * Seeded with deployer defaults per spec §6: {@code ~/.veto/users/{vetoUserId}/}, {@code
     * ~/.ssh}, {@code ~/.aws}, {@code ~/.gnupg}, plus {@code <root>/.env} for each workspace root.
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
     * Seeded with deployer defaults plus owner-issued shared-grant paths (Part 3.6 /
     * group_screening §1.3). Each shared-grant entry is a workspace root the owning user has
     * explicitly shared with the requesting user; the requesting user gets {@code READ_ONLY} or
     * {@code READ_WRITE} on it per the grant mode.
     */
    public static @NonNull ProtectedSet withSharedGrants(
            @NonNull String vetoUserId,
            @NonNull List<Path> workspaceRoots,
            List<SharedGrant> sharedGrants) {
        ProtectedSet base = withDeployerDefaults(vetoUserId, workspaceRoots);
        if (sharedGrants == null || sharedGrants.isEmpty()) {
            return base;
        }
        Set<Path> paths = new HashSet<>(base.paths());
        for (SharedGrant g : sharedGrants) {
            if (g == null) {
                continue;
            }
            paths.add(g.rootPath());
        }
        return new ProtectedSet(paths);
    }

    /**
     * Returns a copy of this set with additional system-protected paths merged in. Used to shield
     * the application's own config/audit material (application.yml, ./config/, ./audit/) from the
     * agent under every non-{@code FULL_ACCESS} policy - see {@link
     * #standardSystemProtected(Path)}.
     */
    public @NonNull ProtectedSet withSystemProtected(@NonNull Collection<Path> systemPaths) {
        if (systemPaths.isEmpty()) {
            return this;
        }
        Set<Path> merged = new HashSet<>(this.paths);
        for (Path p : systemPaths) {
            if (p != null) {
                merged.add(p);
            }
        }
        return new ProtectedSet(merged);
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

    /**
     * A user-issued grant (Part 3.6 / group_screening §1.3). {@code rootPath} is the workspace root
     * being shared; {@code mode} is the access level. The grant is a per-user authorization — it is
     * checked when {@code DangerComputation} runs under {@link DeployerPolicy#TENANT}.
     */
    public record SharedGrant(@NonNull Path rootPath, @NonNull GrantMode mode) {}

    public enum GrantMode {
        READ_ONLY,
        READ_WRITE
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
        return in.stream().map(p -> p.toAbsolutePath().normalize()).collect(Collectors.toSet());
    }
}
