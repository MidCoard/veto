package top.focess.veto.agent.screening;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeployerPolicyConfigurationTest {
    @Test
    void fullAccessAndProtectedNeedNoScopedRoots() {
        DeployerPolicyConfiguration configuration = new DeployerPolicyConfiguration();
        assertDoesNotThrow(configuration::validate);

        configuration.setDeployerPolicy(DeployerPolicy.PROTECTED);
        assertDoesNotThrow(configuration::validate);
    }

    @Test
    void sandboxedRequiresSandboxedRoots() {
        DeployerPolicyConfiguration configuration = new DeployerPolicyConfiguration();
        configuration.setDeployerPolicy(DeployerPolicy.SANDBOXED);
        assertThrows(IllegalStateException.class, configuration::validate);

        configuration.getSandboxed().setRoots(List.of("C:/sandbox"));
        assertDoesNotThrow(configuration::validate);
    }

    @Test
    void tenantRequiresTenantRootsInsteadOfBorrowingSandboxedRoots() {
        DeployerPolicyConfiguration configuration = new DeployerPolicyConfiguration();
        configuration.setDeployerPolicy(DeployerPolicy.TENANT);
        configuration.getSandboxed().setRoots(List.of("C:/sandbox"));
        assertThrows(IllegalStateException.class, configuration::validate);

        configuration.getTenant().setRoots(List.of("C:/tenants"));
        assertDoesNotThrow(configuration::validate);
    }
}
