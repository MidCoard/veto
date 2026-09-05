package top.focess.veto.agent.capability;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.builtin.RunCommandTool;
import top.focess.veto.agent.tool.builtin.ViewTaskTool;
import top.focess.veto.agent.web.SearchProvider;
import top.focess.veto.agent.web.WebSearchTool;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.sandbox.SandboxManager;

class NativeCapabilityBoundaryTest {
    @Test
    void missingPermitFailsBeforeProcessTaskOrNetworkAccess() {
        ToolCallContextHolder.clear();
        SandboxManager sandbox = mock(ToolDocs.nonNullClass(SandboxManager.class));
        BackgroundTaskManager tasks = mock(ToolDocs.nonNullClass(BackgroundTaskManager.class));
        SearchProvider provider = mock(ToolDocs.nonNullClass(SearchProvider.class));
        var process = new ProcessExecutionCapabilityImpl(sandbox, tasks, new ObjectMapper());
        var task = new TaskControlCapabilityImpl(tasks, new ObjectMapper());
        var network = new NetworkEgressCapabilityImpl(provider, 5, 1000, false);
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () ->
                        process.runCommand(
                                new RunCommandTool.Args(
                                        List.of(
                                                new RunCommandTool.CommandInput(
                                                        "java", List.of("-version"))),
                                        null,
                                        0)));
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> task.viewTask(new ViewTaskTool.Args(null)));
        assertThrows(
                ToolDocs.nonNullClass(SecurityException.class),
                () -> network.search(new WebSearchTool.Args("query", null, null)));
        verifyNoInteractions(sandbox, tasks, provider);
    }
}
