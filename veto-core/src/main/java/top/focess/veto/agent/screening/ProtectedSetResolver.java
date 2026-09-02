package top.focess.veto.agent.screening;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.observability.ObservabilityConfiguration;
import top.focess.veto.vault.CredentialVaultConfiguration;

/**
 * Resolves the effective protected set from deployer config, user identity, and workspace roots.
 */
@Component
public class ProtectedSetResolver {
    private final @NonNull DeployerPolicyConfiguration policyConfiguration;
    private final @NonNull ObservabilityConfiguration observability;
    private final @NonNull CredentialVaultConfiguration vault;
    private final @NonNull Path launchDirectory;

    @Autowired
    public ProtectedSetResolver(
            @NonNull DeployerPolicyConfiguration policyConfiguration,
            @NonNull ObservabilityConfiguration observability,
            @NonNull CredentialVaultConfiguration vault) {
        this(
                policyConfiguration,
                observability,
                vault,
                Path.of(System.getProperty("user.dir", ".")));
    }

    ProtectedSetResolver(
            @NonNull DeployerPolicyConfiguration policyConfiguration,
            @NonNull ObservabilityConfiguration observability,
            @NonNull CredentialVaultConfiguration vault,
            @NonNull Path launchDirectory) {
        this.policyConfiguration = policyConfiguration;
        this.observability = observability;
        this.vault = vault;
        this.launchDirectory = launchDirectory.toAbsolutePath().normalize();
    }

    public @NonNull ProtectedSet resolve(
            @NonNull DeployerPolicy policy,
            @NonNull String vetoUserId,
            @NonNull Workspace workspace) {
        if (policy == DeployerPolicy.FULL_ACCESS) {
            return ProtectedSet.empty();
        }
        DeployerPolicyConfiguration.PathProtection configuration =
                policyConfiguration.protectionFor(policy);
        Set<Path> paths = new HashSet<>();
        if (configuration.isIncludeDefaults()) {
            paths.addAll(
                    ProtectedSet.withDeployerDefaults(vetoUserId, workspace.hostRoots()).paths());
        }
        if (configuration.isIncludeApplicationPaths()) {
            paths.addAll(ProtectedSet.standardSystemProtected(launchDirectory));
            paths.add(resolveConfiguredPath(observability.getAuditLogPath()));
            paths.add(resolveConfiguredPath(vault.getVaultHome()));
        }
        for (String configured : configuration.getPaths()) {
            if (configured != null && !configured.isBlank()) {
                paths.add(resolveConfiguredPath(configured));
            }
        }
        return new ProtectedSet(paths);
    }

    private @NonNull Path resolveConfiguredPath(@NonNull String raw) {
        String expanded = raw;
        if (raw.startsWith("~/") || raw.startsWith("~\\")) {
            expanded = System.getProperty("user.home", "") + raw.substring(1);
        }
        Path path = Path.of(expanded);
        return (path.isAbsolute() ? path : launchDirectory.resolve(path)).normalize();
    }
}
