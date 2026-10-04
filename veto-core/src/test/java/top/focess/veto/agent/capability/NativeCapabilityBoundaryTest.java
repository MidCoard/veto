package top.focess.veto.agent.capability;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.integration.plugins.ProcessHostFixture;
import top.focess.veto.sandbox.TestSandboxFactory;

class NativeCapabilityBoundaryTest {
    @Test
    void missingPermitFailsBeforeProcessOrNetworkAccess() {
        ToolCallContextHolder.clear();
        try (var fixture =
                new ProcessHostFixture(
                        TestSandboxFactory.uncontainedSubprocesses(), List.of(), false)) {
            assertThrows(SecurityException.class, fixture.host::runApproved);
            assertThrows(SecurityException.class, fixture.host::startApproved);
            var network =
                    new NetworkEgressCapabilityImpl(
                            5, 1000, false, Mockito.mock(ImportedCredentialLeases.class));
            assertThrows(SecurityException.class, () -> network.openApprovedDestination("url"));
        }
    }
}
