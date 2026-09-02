package top.focess.veto.agent.screening;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.observability.ObservabilityConfiguration;
import top.focess.veto.vault.CredentialVaultConfiguration;

@SuppressWarnings("initialization.field.uninitialized")
class ProtectedSetResolverTest {
    @TempDir @NonNull Path root;

    @Test
    void fullAccessAlwaysHasNoHardPathDenyList() {
        DeployerPolicyConfiguration configuration = new DeployerPolicyConfiguration();
        configuration
                .getProtectedPolicy()
                .setPaths(List.of(root.resolve("custom-secret").toString()));

        ProtectedSet resolved =
                resolver(configuration).resolve(DeployerPolicy.FULL_ACCESS, "u", workspace());

        assertTrue(resolved.paths().isEmpty());
    }

    @Test
    void protectedPolicyCombinesConfiguredAndContextualPaths() {
        DeployerPolicyConfiguration configuration = new DeployerPolicyConfiguration();
        Path custom = root.resolve("custom-secret");
        configuration.getProtectedPolicy().setPaths(List.of(custom.toString()));

        ProtectedSet resolved =
                resolver(configuration).resolve(DeployerPolicy.PROTECTED, "u", workspace());

        assertTrue(resolved.covers(custom.resolve("token.txt")));
        assertTrue(resolved.covers(root.resolve("workspace/.env")));
        assertTrue(resolved.covers(root.resolve("launch/application.yml")));
    }

    @Test
    void sandboxedAndTenantUseTheirOwnNestedConfiguration() {
        DeployerPolicyConfiguration configuration = new DeployerPolicyConfiguration();
        Path protectedOnly = root.resolve("protected-only");
        Path sandboxedOnly = root.resolve("sandboxed-only");
        Path tenantOnly = root.resolve("tenant-only");
        configuration.getProtectedPolicy().setPaths(List.of(protectedOnly.toString()));
        configuration.getSandboxed().getProtection().setPaths(List.of(sandboxedOnly.toString()));
        configuration.getTenant().getProtection().setPaths(List.of(tenantOnly.toString()));

        ProtectedSetResolver resolver = resolver(configuration);
        ProtectedSet sandboxed = resolver.resolve(DeployerPolicy.SANDBOXED, "u", workspace());
        ProtectedSet tenant = resolver.resolve(DeployerPolicy.TENANT, "u", workspace());

        assertTrue(sandboxed.covers(sandboxedOnly));
        assertFalse(sandboxed.covers(protectedOnly));
        assertFalse(sandboxed.covers(tenantOnly));
        assertTrue(tenant.covers(tenantOnly));
        assertFalse(tenant.covers(protectedOnly));
        assertFalse(tenant.covers(sandboxedOnly));
    }

    private @NonNull ProtectedSetResolver resolver(
            @NonNull DeployerPolicyConfiguration configuration) {
        ObservabilityConfiguration observability = new ObservabilityConfiguration();
        observability.setAuditLogPath("audit-data");
        CredentialVaultConfiguration vault = new CredentialVaultConfiguration();
        vault.setVaultHome("vault-data");
        return new ProtectedSetResolver(
                configuration, observability, vault, root.resolve("launch"));
    }

    private @NonNull Workspace workspace() {
        return Workspace.single(root.resolve("workspace"), PathMode.REAL);
    }
}
