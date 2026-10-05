package top.focess.veto.agent.workspace;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.DeployerPolicyConfiguration;
import top.focess.veto.agent.screening.ProtectedSetResolver;
import top.focess.veto.security.HostPathInput;
import top.focess.veto.vault.UserRegistry;

/**
 * Admits session-declared roots against deployer-owned filesystem mounts.
 *
 * <p>A client declaration selects authorized directories; it never creates ownership. SANDBOXED
 * accepts roots only below {@code veto.security.sandboxed.roots}. TENANT uses its own {@code
 * veto.security.tenant.roots} and additionally confines each user below a mount's direct {@code
 * <username>} child. The account UUID selects the user; the existing directory label is retained.
 * TENANT selects direct child workspaces using logical paths.
 */
@Component
public final class WorkspaceAdmissionPolicy {

    private final @NonNull DeployerPolicyConfiguration configuration;
    private final @NonNull ProtectedSetResolver protectedSets;
    private final @NonNull UserRegistry users;

    public WorkspaceAdmissionPolicy(
            @NonNull DeployerPolicyConfiguration configuration,
            @NonNull ProtectedSetResolver protectedSets,
            @NonNull UserRegistry users) {
        this.configuration = configuration;
        this.protectedSets = protectedSets;
        this.users = users;
    }

    /**
     * Canonicalizes nonempty roots and validates deployment scope and protected paths without
     * filesystem mutation. SessionService checks ownership availability under its creation lock.
     */
    public @NonNull List<@NonNull Path> validateRoots(
            @NonNull UUID userId, @NonNull List<@NonNull String> workspaceRoots) {
        if (workspaceRoots.isEmpty()) {
            throw new IllegalArgumentException("no workspace roots declared");
        }
        var supplied =
                workspaceRoots.stream()
                        .map(
                                root -> {
                                    if (root.isBlank() || root.contains(",")) {
                                        throw new IllegalArgumentException(
                                                "workspace root must be nonblank and contain no comma");
                                    }
                                    return fromClientPath(userId, root);
                                })
                        .toList();
        var deployerPolicy = configuration.getDeployerPolicy();
        var deployerRoots = configuration.canonicalRoots();
        if (deployerPolicy == DeployerPolicy.SANDBOXED || deployerPolicy == DeployerPolicy.TENANT) {
            if (deployerRoots.isEmpty()) {
                throw new IllegalStateException(
                        deployerPolicy + " requires at least one configured policy root");
            }
            var authorizedBases =
                    deployerPolicy == DeployerPolicy.TENANT ? tenantBases(userId) : deployerRoots;
            for (Path path : supplied) {
                if (authorizedBases.stream().noneMatch(path::startsWith)
                        || (deployerPolicy == DeployerPolicy.TENANT
                                && authorizedBases.stream()
                                        .noneMatch(base -> base.equals(path.getParent())))) {
                    throw new IllegalArgumentException(
                            "workspace root is outside the deployer-authorized scope");
                }
            }
        }
        if (deployerPolicy != DeployerPolicy.FULL_ACCESS) {
            var workspace =
                    new Workspace(
                            supplied.stream()
                                    .map(path -> WorkspaceRoot.of(path, TrustMarker.OWNED))
                                    .toList(),
                            PathMode.REAL,
                            0);
            for (Path protectedPath :
                    protectedSets.resolve(deployerPolicy, userId, workspace).paths()) {
                var canonicalProtected =
                        HostPathInput.canonicalForCreation(protectedPath, "protected path");
                if (supplied.stream().anyMatch(root -> root.startsWith(canonicalProtected))) {
                    throw new IllegalArgumentException("workspace root is inside a protected path");
                }
            }
        }
        return supplied;
    }

    /** Canonicalizes declared roots, including filesystem roots, without creating directories. */
    public static @NonNull List<@NonNull Path> canonicalRoots(@NonNull String workspaceRoots) {
        var supplied =
                Arrays.stream(workspaceRoots.split(","))
                        .map(String::trim)
                        .filter(root -> !root.isEmpty())
                        .map(root -> HostPathInput.absoluteNormalized(root, "workspace root"))
                        .map(path -> HostPathInput.canonicalForCreation(path, "workspace root"))
                        .toList();
        if (supplied.isEmpty()) {
            throw new IllegalArgumentException("no workspace roots declared");
        }
        return supplied;
    }

    private @NonNull List<@NonNull Path> tenantBases(@NonNull UUID userId) {
        var username =
                users.findByUserId(userId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown user"))
                        .getUsername();
        if (!UserRegistry.isValidUsername(username))
            throw new IllegalArgumentException("Invalid tenant directory label");
        List<Path> result = new ArrayList<>(configuration.canonicalRoots().size());
        for (Path root : configuration.canonicalRoots().stream().sorted().toList()) {
            var mapped = root.resolve(username);
            var canonical = HostPathInput.canonicalForCreation(mapped, "tenant workspace root");
            if (!canonical.equals(mapped))
                throw new IllegalArgumentException("invalid tenant mapping");
            result.add(canonical);
        }
        return List.copyOf(result);
    }

    /** Deployment bases visible to the userId. */
    public @NonNull List<@NonNull Path> browseBases(@NonNull UUID userId) {
        return switch (configuration.getDeployerPolicy()) {
            case TENANT -> tenantBases(userId);
            case SANDBOXED -> configuration.canonicalRoots().stream().sorted().toList();
            case FULL_ACCESS, PROTECTED ->
                    StreamSupport.stream(
                                    FileSystems.getDefault().getRootDirectories().spliterator(),
                                    false)
                            .toList();
        };
    }

    /** Whether the deployment requires opaque userId-mapped paths at the client boundary. */
    public boolean tenant() {
        return configuration.getDeployerPolicy() == DeployerPolicy.TENANT;
    }

    /** Resolves a client path without exposing host mapping through parser errors. */
    public @NonNull Path fromClientPath(@NonNull UUID userId, @NonNull String input) {
        if (!tenant())
            return HostPathInput.canonicalForCreation(
                    HostPathInput.absoluteNormalized(input, "path"), "path");
        if (!input.startsWith("/") || input.contains("\\") || input.contains(","))
            throw new IllegalArgumentException("invalid workspace path");
        var segments = input.substring(1).split("/", -1);
        try {
            int index = Integer.parseInt(segments[0]);
            var bases = tenantBases(userId);
            if (index < 0 || index >= bases.size())
                throw new IllegalArgumentException("invalid workspace base");
            Path result = bases.get(index);
            for (int i = 1; i < segments.length; i++) {
                var segment = segments[i];
                if (segment.isBlank()
                        || segment.equals(".")
                        || segment.equals("..")
                        || segment.contains(":"))
                    throw new IllegalArgumentException("invalid workspace path");
                result = result.resolve(segment);
            }
            var canonical = HostPathInput.canonicalForCreation(result, "workspace path");
            if (!canonical.startsWith(bases.get(index)))
                throw new IllegalArgumentException("invalid workspace path");
            return canonical;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid workspace base");
        }
    }

    /** Produces a logical path for TENANT, preserving ordinary host paths for other policies. */
    public @NonNull String toClientPath(@NonNull UUID userId, @NonNull Path path) {
        if (!tenant()) return path.toString();
        var bases = tenantBases(userId);
        for (int i = 0; i < bases.size(); i++) {
            var base = bases.get(i);
            if (path.startsWith(base)) {
                var relative = base.relativize(path).toString().replace('\\', '/');
                return "/" + i + (relative.isEmpty() ? "" : "/" + relative);
            }
        }
        throw new IllegalStateException("workspace mapping unavailable");
    }

    /** Tenant browsing/creation stops at the mapped base; selection stops at direct children. */
    public boolean canBrowse(
            @NonNull UUID userId, @NonNull Path path, @NonNull Collection<@NonNull Path> occupied) {
        return canAccess(userId, path, occupied)
                && (!tenant() || browseBases(userId).contains(path));
    }

    /** Tests a canonical target against policy and other owners' persisted claims. */
    public boolean canAccess(
            @NonNull UUID userId, @NonNull Path path, @NonNull Collection<@NonNull Path> occupied) {
        if (occupied.stream().anyMatch(path::startsWith)) return false;
        var policy = configuration.getDeployerPolicy();
        if ((policy == DeployerPolicy.SANDBOXED || policy == DeployerPolicy.TENANT)
                && browseBases(userId).stream().noneMatch(path::startsWith)) return false;
        if (policy == DeployerPolicy.FULL_ACCESS) return true;
        var workspace =
                new Workspace(List.of(WorkspaceRoot.of(path, TrustMarker.OWNED)), PathMode.REAL, 0);
        return protectedSets.resolve(policy, userId, workspace).paths().stream()
                .map(entry -> HostPathInput.canonicalForCreation(entry, "protected path"))
                .noneMatch(path::startsWith);
    }

    /** A selection cannot contain another userId's workspace. */
    public boolean canSelect(
            @NonNull UUID userId, @NonNull Path path, @NonNull Collection<@NonNull Path> occupied) {
        return !path.toString().contains(",")
                && canAccess(userId, path, occupied)
                && (!tenant()
                        || browseBases(userId).stream()
                                .anyMatch(base -> base.equals(path.getParent())))
                && occupied.stream().noneMatch(entry -> entry.startsWith(path));
    }
}
