package top.focess.veto.agent.capability;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.web.SearchOptions;
import top.focess.veto.agent.web.SearchProvider;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.sandbox.ChainMode;
import top.focess.veto.sandbox.SandboxManager;

class NativeCapabilityBoundaryTest {
    @Test
    void missingPermitFailsBeforeProcessTaskOrNetworkAccess() {
        ToolCallContextHolder.clear();
        SandboxManager sandbox = mock(ToolDocs.nonNullClass(SandboxManager.class));
        BackgroundTaskManager tasks = mock(ToolDocs.nonNullClass(BackgroundTaskManager.class));
        SearchProvider provider = mock(ToolDocs.nonNullClass(SearchProvider.class));
        var process = new ProcessExecutionCapabilityImpl(sandbox, tasks);
        var task = new TaskControlCapabilityImpl(tasks);
        var network = new NetworkEgressCapabilityImpl(provider, 5, 1000, false);
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> process.run(List.of(), ChainMode.STOP_ON_FAILURE, Duration.ZERO, false));
        assertThrows(ToolDocs.nonNullClass(SecurityException.class), task::list);
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> network.search("query", new SearchOptions(null, null, 3)));
        verifyNoInteractions(sandbox, tasks, provider);
    }
}
