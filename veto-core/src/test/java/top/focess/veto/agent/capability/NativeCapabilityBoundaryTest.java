package top.focess.veto.agent.capability;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.integration.plugins.ProcessHostFixture;
import top.focess.veto.sandbox.TestSandboxFactory;

class NativeCapabilityBoundaryTest {
    @Test
    void missingPermitFailsBeforeProcessOrNetworkAccess() {
        ToolCallContextHolder.clear();
        try (var fixture =
                new ProcessHostFixture(
                        TestSandboxFactory.uncontainedSubprocesses(), List.of(), false)) {
            assertThrows(ToolDocs.nonNullClass(SecurityException.class), fixture.host::runApproved);
            assertThrows(
                    ToolDocs.nonNullClass(SecurityException.class), fixture.host::startApproved);
            var network = new NetworkEgressCapabilityImpl(5, 1000, false);
            assertThrows(
                    ToolDocs.nonNullClass(SecurityException.class),
                    () -> network.openApprovedDestination("url"));
        }
    }
}
