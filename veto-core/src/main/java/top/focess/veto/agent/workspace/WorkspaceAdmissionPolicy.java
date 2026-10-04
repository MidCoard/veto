package top.focess.veto.agent.workspace;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.DeployerPolicyConfiguration;
import top.focess.veto.agent.screening.ProtectedSetResolver;
import top.focess.veto.security.HostPathInput;

/**
 * Admits session-declared roots against deployer-owned filesystem mounts.
 *
 * <p>A client declaration selects authorized directories; it never creates ownership. SANDBOXED
 * accepts roots only below {@code veto.security.sandboxed.roots}. TENANT uses its own {@code
 * veto.security.tenant.roots} and additionally confines each user below a mount's direct {@code
 * <owner>} child. TENANT selects direct child workspaces using logical paths.
 */
@Component
public final class WorkspaceAdmissionPolicy {

    private final @NonNull DeployerPolicyConfiguration configuration;
    private final @NonNull ProtectedSetResolver protectedSets;

    public WorkspaceAdmissionPolicy(
            @NonNull DeployerPolicyConfiguration configuration,
            @NonNull ProtectedSetResolver protectedSets) {
        this.configuration = configuration;
        this.protectedSets = protectedSets;
    }

    /** Validates and canonicalizes a CSV declaration without mutating the filesystem. */
    public @NonNull List<@NonNull Path> admit(
            @NonNull String owner, @NonNull String workspaceRoots) {
        var supplied =
                configuration.getDeployerPolicy() == DeployerPolicy.TENANT
                        ? Arrays.stream(workspaceRoots.split(","))
                                .map(String::trim)
                                .map(root -> fromClientPath(owner, root))
                                .toList()
                        : canonicalRoots(workspaceRoots);
        var deployerPolicy = configuration.getDeployerPolicy();
        var deployerRoots = configuration.canonicalRoots();
        if (deployerPolicy == DeployerPolicy.SANDBOXED || deployerPolicy == DeployerPolicy.TENANT) {
            if (deployerRoots.isEmpty()) {
                throw new IllegalStateException(
                        deployerPolicy + " requires at least one configured policy root");
            }
            var authorizedBases =
                    deployerPolicy == DeployerPolicy.TENANT ? tenantBases(owner) : deployerRoots;
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
                    protectedSets.resolve(deployerPolicy, owner, workspace).paths()) {
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

    private @NonNull List<@NonNull Path> tenantBases(@NonNull String owner) {
        if (owner.isBlank()
                || owner.equals(".")
                || owner.equals("..")
                || owner.contains("/")
                || owner.contains("\\")
                || owner.contains(":")) {
            throw new IllegalArgumentException("owner is not safe for tenant workspace mapping");
        }
        List<Path> result = new ArrayList<>(configuration.canonicalRoots().size());
        for (Path root : configuration.canonicalRoots().stream().sorted().toList()) {
            var mapped = root.resolve(owner);
            var canonical = HostPathInput.canonicalForCreation(mapped, "tenant workspace root");
            if (!canonical.equals(mapped))
                throw new IllegalArgumentException("invalid tenant mapping");
            result.add(canonical);
        }
        return List.copyOf(result);
    }

    /** Deployment bases visible to the owner. */
    public @NonNull List<@NonNull Path> browseBases(@NonNull String owner) {
        return switch (configuration.getDeployerPolicy()) {
            case TENANT -> tenantBases(owner);
            case SANDBOXED -> configuration.canonicalRoots().stream().sorted().toList();
            case FULL_ACCESS, PROTECTED ->
                    StreamSupport.stream(
                                    FileSystems.getDefault().getRootDirectories().spliterator(),
                                    false)
                            .toList();
        };
    }

    /** Whether the deployment requires opaque owner-mapped paths at the client boundary. */
    public boolean tenant() {
        return configuration.getDeployerPolicy() == DeployerPolicy.TENANT;
    }

    /** Resolves a client path without exposing host mapping through parser errors. */
    public @NonNull Path fromClientPath(@NonNull String owner, @NonNull String input) {
        if (!tenant())
            return HostPathInput.canonicalForCreation(
                    HostPathInput.absoluteNormalized(input, "path"), "path");
        if (!input.startsWith("/") || input.contains("\\") || input.contains(","))
            throw new IllegalArgumentException("invalid workspace path");
        var segments = input.substring(1).split("/", -1);
        try {
            int index = Integer.parseInt(segments[0]);
            var bases = tenantBases(owner);
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
    public @NonNull String toClientPath(@NonNull String owner, @NonNull Path path) {
        if (!tenant()) return path.toString();
        var bases = tenantBases(owner);
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
            @NonNull String owner,
            @NonNull Path path,
            @NonNull Collection<@NonNull Path> occupied) {
        return canAccess(owner, path, occupied) && (!tenant() || browseBases(owner).contains(path));
    }

    /** Tests a canonical target against policy and other owners' persisted claims. */
    public boolean canAccess(
            @NonNull String owner,
            @NonNull Path path,
            @NonNull Collection<@NonNull Path> occupied) {
        if (occupied.stream().anyMatch(path::startsWith)) return false;
        var policy = configuration.getDeployerPolicy();
        if ((policy == DeployerPolicy.SANDBOXED || policy == DeployerPolicy.TENANT)
                && browseBases(owner).stream().noneMatch(path::startsWith)) return false;
        if (policy == DeployerPolicy.FULL_ACCESS) return true;
        var workspace =
                new Workspace(List.of(WorkspaceRoot.of(path, TrustMarker.OWNED)), PathMode.REAL, 0);
        return protectedSets.resolve(policy, owner, workspace).paths().stream()
                .map(entry -> HostPathInput.canonicalForCreation(entry, "protected path"))
                .noneMatch(path::startsWith);
    }

    /** A selection cannot contain another owner's workspace. */
    public boolean canSelect(
            @NonNull String owner,
            @NonNull Path path,
            @NonNull Collection<@NonNull Path> occupied) {
        return !path.toString().contains(",")
                && canAccess(owner, path, occupied)
                && (!tenant()
                        || browseBases(owner).stream()
                                .anyMatch(base -> base.equals(path.getParent())))
                && occupied.stream().noneMatch(entry -> entry.startsWith(path));
    }
}
