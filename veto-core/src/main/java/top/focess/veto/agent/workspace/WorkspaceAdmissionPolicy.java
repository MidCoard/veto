package top.focess.veto.agent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.security.HostPathInput;

/**
 * Admits session-declared roots against deployer-owned filesystem mounts.
 *
 * <p>A client declaration selects authorized directories; it never creates ownership. SANDBOXED
 * accepts roots only below configured {@code veto.workspace.root/roots} mounts. TENANT additionally
 * confines each user below a mount's direct {@code <owner>} child.
 */
@Component
public final class WorkspaceAdmissionPolicy {

    private final @NonNull List<@NonNull Path> deployerRoots;
    private final @NonNull DeployerPolicy deployerPolicy;
    private final boolean canonicalize;

    @Autowired
    public WorkspaceAdmissionPolicy(
            @NonNull Workspace defaultWorkspace,
            @Value("${veto.security.deployer-policy:FULL_ACCESS}")
                    @NonNull String deployerPolicyRaw) {
        this(defaultWorkspace.hostRoots(), DeployerPolicy.parse(deployerPolicyRaw));
    }

    public WorkspaceAdmissionPolicy(
            @NonNull List<@NonNull Path> deployerRoots, @NonNull DeployerPolicy deployerPolicy) {
        this(deployerRoots, deployerPolicy, true);
    }

    private WorkspaceAdmissionPolicy(
            @NonNull List<@NonNull Path> deployerRoots,
            @NonNull DeployerPolicy deployerPolicy,
            boolean canonicalize) {
        this.deployerRoots =
                deployerRoots.stream()
                        .map(path -> canonicalForCreation(path, "configured workspace root"))
                        .toList();
        this.deployerPolicy = deployerPolicy;
        this.canonicalize = canonicalize;
    }

    /** Unrestricted admission for unit tests and legacy embedded callers. */
    public static @NonNull WorkspaceAdmissionPolicy unrestricted() {
        return new WorkspaceAdmissionPolicy(List.of(), DeployerPolicy.FULL_ACCESS, false);
    }

    /** Validates and canonicalizes a CSV declaration without mutating the filesystem. */
    public @NonNull List<@NonNull Path> admit(
            @NonNull String owner, @NonNull String workspaceRoots) {
        List<Path> supplied =
                java.util.Arrays.stream(workspaceRoots.split(","))
                        .map(String::trim)
                        .filter(root -> !root.isEmpty())
                        .map(root -> HostPathInput.absoluteNormalized(root, "workspace root"))
                        .map(
                                path ->
                                        canonicalize
                                                ? canonicalForCreation(path, "workspace root")
                                                : path)
                        .toList();
        if (supplied.isEmpty()) {
            throw new IllegalArgumentException("no workspace roots declared");
        }
        if (deployerPolicy != DeployerPolicy.SANDBOXED && deployerPolicy != DeployerPolicy.TENANT) {
            return supplied;
        }
        if (deployerRoots.isEmpty()) {
            throw new IllegalStateException(
                    deployerPolicy + " requires at least one configured veto.workspace root");
        }

        List<Path> authorizedBases =
                deployerPolicy == DeployerPolicy.TENANT ? tenantBases(owner) : deployerRoots;
        for (Path path : supplied) {
            if (authorizedBases.stream().noneMatch(path::startsWith)) {
                throw new IllegalArgumentException(
                        "workspace root is outside the deployer-authorized scope: " + path);
            }
        }
        return supplied;
    }

    private @NonNull List<@NonNull Path> tenantBases(@NonNull String owner) {
        if (owner.isBlank()
                || owner.equals(".")
                || owner.equals("..")
                || owner.contains("/")
                || owner.contains("\\")) {
            throw new IllegalArgumentException("owner is not safe for tenant workspace mapping");
        }
        List<Path> result = new ArrayList<>(deployerRoots.size());
        for (Path root : deployerRoots) {
            result.add(canonicalForCreation(root.resolve(owner), "tenant workspace root"));
        }
        return List.copyOf(result);
    }

    /** Resolves existing segments so a symlink cannot disguise an out-of-scope future child. */
    static @NonNull Path canonicalForCreation(@NonNull Path path, @NonNull String fieldName) {
        Path absolute = path.toAbsolutePath().normalize();
        Deque<Path> missing = new ArrayDeque<>();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            Path name = existing.getFileName();
            if (name != null) {
                missing.addFirst(name);
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IllegalArgumentException(fieldName + " has no existing filesystem ancestor");
        }
        try {
            Path resolved = existing.toRealPath();
            for (Path segment : missing) {
                resolved = resolved.resolve(segment);
            }
            return resolved.normalize();
        } catch (IOException e) {
            throw new IllegalArgumentException(fieldName + " cannot be canonicalized", e);
        }
    }
}
