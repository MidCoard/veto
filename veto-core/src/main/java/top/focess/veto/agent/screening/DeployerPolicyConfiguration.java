package top.focess.veto.agent.screening;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** All deployer-policy configuration, kept in one binding object. */
@Configuration
@ConfigurationProperties(prefix = "veto.security")
public class DeployerPolicyConfiguration {
    private @NonNull DeployerPolicy deployerPolicy = DeployerPolicy.FULL_ACCESS;
    private @NonNull PathProtection protectedPolicy = new PathProtection();
    private @NonNull ScopedPolicy sandboxed = new ScopedPolicy();
    private @NonNull ScopedPolicy tenant = new ScopedPolicy();

    public @NonNull DeployerPolicy getDeployerPolicy() {
        return deployerPolicy;
    }

    public void setDeployerPolicy(@NonNull DeployerPolicy deployerPolicy) {
        this.deployerPolicy = deployerPolicy;
    }

    public @NonNull PathProtection getProtectedPolicy() {
        return protectedPolicy;
    }

    public void setProtectedPolicy(@NonNull PathProtection protectedPolicy) {
        this.protectedPolicy = protectedPolicy;
    }

    public @NonNull ScopedPolicy getSandboxed() {
        return sandboxed;
    }

    public void setSandboxed(@NonNull ScopedPolicy sandboxed) {
        this.sandboxed = sandboxed;
    }

    public @NonNull ScopedPolicy getTenant() {
        return tenant;
    }

    public void setTenant(@NonNull ScopedPolicy tenant) {
        this.tenant = tenant;
    }

    @PostConstruct
    void validate() {
        switch (deployerPolicy) {
            case FULL_ACCESS, PROTECTED -> {
                // These policies require no scoped roots.
            }
            case SANDBOXED -> requireRoots("sandboxed", sandboxed.roots);
            case TENANT -> requireRoots("tenant", tenant.roots);
        }
    }

    public @NonNull PathProtection protectionFor(@NonNull DeployerPolicy policy) {
        return switch (policy) {
            case FULL_ACCESS ->
                    throw new IllegalArgumentException("FULL_ACCESS has no protected set");
            case PROTECTED -> protectedPolicy;
            case SANDBOXED -> sandboxed.protection;
            case TENANT -> tenant.protection;
        };
    }

    public @NonNull List<@NonNull String> rootsFor(@NonNull DeployerPolicy policy) {
        return switch (policy) {
            case FULL_ACCESS, PROTECTED -> List.of();
            case SANDBOXED -> sandboxed.roots;
            case TENANT -> tenant.roots;
        };
    }

    private static void requireRoots(
            @NonNull String policyName, @NonNull List<@NonNull String> roots) {
        if (roots.isEmpty() || roots.stream().allMatch(String::isBlank)) {
            throw new IllegalStateException(
                    policyName.toUpperCase(java.util.Locale.ROOT)
                            + " requires at least one veto.security."
                            + policyName
                            + ".roots entry");
        }
    }

    /** Protected-path settings shared structurally by the policies that use them. */
    public static class PathProtection {
        private boolean includeDefaults = true;
        private boolean includeApplicationPaths = true;
        private @NonNull List<@NonNull String> paths = new ArrayList<>();

        public boolean isIncludeDefaults() {
            return includeDefaults;
        }

        public void setIncludeDefaults(boolean includeDefaults) {
            this.includeDefaults = includeDefaults;
        }

        public boolean isIncludeApplicationPaths() {
            return includeApplicationPaths;
        }

        public void setIncludeApplicationPaths(boolean includeApplicationPaths) {
            this.includeApplicationPaths = includeApplicationPaths;
        }

        public @NonNull List<@NonNull String> getPaths() {
            return paths;
        }

        public void setPaths(@NonNull List<@NonNull String> paths) {
            this.paths = new ArrayList<>(paths);
        }
    }

    /** A scoped policy's deployer zones plus its protected-path settings. */
    public static class ScopedPolicy {
        private @NonNull List<@NonNull String> roots = new ArrayList<>();
        private @NonNull PathProtection protection = new PathProtection();

        public @NonNull List<@NonNull String> getRoots() {
            return roots;
        }

        public void setRoots(@NonNull List<@NonNull String> roots) {
            this.roots = new ArrayList<>(roots);
        }

        public @NonNull PathProtection getProtection() {
            return protection;
        }

        public void setProtection(@NonNull PathProtection protection) {
            this.protection = protection;
        }
    }
}
