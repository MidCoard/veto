package top.focess.veto.agent.tool.builtin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.capability.ProcessExecutionCapabilityImpl;
import top.focess.veto.agent.capability.TaskControlCapabilityImpl;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.tool.CapabilityTestCalls;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolExecutionException;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.bus.TaskEventBridge;
import top.focess.veto.group.GroupRegistry;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.model.SessionEntity;
import top.focess.veto.model.SessionRepository;
import top.focess.veto.monitor.MonitorRecord;
import top.focess.veto.monitor.MonitorRepository;
import top.focess.veto.monitor.MonitorService;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.sandbox.SandboxManager;
import top.focess.veto.sandbox.TestSandboxFactory;

/**
 * Exercises the {@code run_task} / {@code view_task} / {@code stop_task} tools end-to-end through
 * their beans (no Spring): launch a quick-exit task, observe it finish via {@code view_task}, and
 * confirm {@code stop_task} is idempotent on an already-exited task.
 */
@SuppressWarnings("initialization.field.uninitialized")
class RunTaskToolTest {

    private final @NonNull ObjectMapper mapper =
            new ObjectMapper().registerModule(new JavaTimeModule());
    private @NonNull BackgroundTaskManager manager;
    private @NonNull RunTaskTool runTask;
    private @NonNull ViewTaskTool status;
    private @NonNull StopTaskTool stop;

    @BeforeEach
    void setUp() {
        manager =
                new BackgroundTaskManager(
                        new SandboxManager(TestSandboxFactory.uncontainedSubprocesses()));
        runTask =
                new RunTaskTool(
                        new ProcessExecutionCapabilityImpl(
                                new SandboxManager(TestSandboxFactory.uncontainedSubprocesses()),
                                manager));
        status = new ViewTaskTool(new TaskControlCapabilityImpl(manager));
        stop = new StopTaskTool(new TaskControlCapabilityImpl(manager));
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent-x",
                        UUID.randomUUID(),
                        null,
                        null,
                        null,
                        ToolResultPresentationMode.BASIC,
                        false,
                        ToolExecutionPermit.empty()));
    }

    @AfterEach
    void tearDown() {
        ToolCallContextHolder.clear();
        manager.stopAll("agent-x");
        manager.shutdown();
    }

    @Test
    void runTaskLaunchesAndStatusReportsExit(@TempDir @NonNull Path tempDir) throws Exception {
        var session = new SessionEntity("owner", "process-origin", tempDir.toString());
        @NonNull SessionRepository sessions = mock();
        when(sessions.findById(session.getId())).thenReturn(Optional.of(session));
        @NonNull MonitorRepository repository = mock();
        var monitors =
                new MonitorService(
                        repository, mapper, new GroupRegistry(), new SessionAgentRegistry());
        var bridge =
                new TaskEventBridge(
                        manager,
                        mock(ToolDocs.nonNullClass(DeltaBroker.class)),
                        sessions,
                        monitors);
        manager.setTaskListener(bridge);
        ToolExecutionPermit permit =
                new ToolExecutionPermit(
                        new ToolCall("run_task", Map.of(), "test-call"),
                        ToolCapability.PROCESS_EXECUTION,
                        null,
                        null,
                        Map.of(),
                        List.of(tempDir),
                        tempDir,
                        DeployerPolicy.FULL_ACCESS,
                        Set.of(),
                        null);
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent-x",
                        UUID.randomUUID(),
                        null,
                        null,
                        UUID.fromString(session.getId()),
                        ToolResultPresentationMode.BASIC,
                        false,
                        permit,
                        "launch-request"));
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        String exe =
                Path.of(System.getProperty("java.home"), "bin", win ? "java.exe" : "java")
                        .toString();
        RunTaskTool.Args args =
                new RunTaskTool.Args(
                        List.of(new RunCommandTool.CommandInput(exe, List.of("-version"))),
                        false,
                        0);

        String startedJson = CapabilityTestCalls.execute(runTask, args);
        JsonNode started = mapper.readTree(startedJson);
        assertEquals("started", started.get("status").asText());
        String taskId = started.get("taskId").asText();
        assertFalse(taskId.isBlank());

        JsonNode statusNode =
                mapper.readTree(
                        CapabilityTestCalls.execute(status, new ViewTaskTool.Args(taskId, true)));
        assertFalse(statusNode.get("alive").asBoolean(), "task should have exited");
        assertEquals(0, statusNode.get("exitCode").asInt(), "java -version exits 0");
        assertFalse(statusNode.get("recentOutput").asText().isBlank(), "output captured");
        var info = manager.status("agent-x", taskId).orElseThrow();
        assertEquals("launch-request", info.requestId());
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (monitors.pending("agent-x", session.getId()).isEmpty()
                && System.nanoTime() < deadline) Thread.sleep(10);
        var event = monitors.pending("agent-x", session.getId()).getFirst();
        assertEquals("launch-request", event.requestId());
        assertEquals(info.taskInstanceId().toString(), event.dispatchId());
        var saved = monitors.list("owner", session.getId()).getFirst();
        var replayed =
                mapper.readValue(
                        mapper.writeValueAsString(saved),
                        ToolDocs.nonNullClass(MonitorRecord.class));
        assertEquals(event, replayed.pending().getFirst());

        // stop_task is idempotent on an already-exited task.
        JsonNode stopped =
                mapper.readTree(CapabilityTestCalls.execute(stop, new StopTaskTool.Args(taskId)));
        assertEquals("already_exited", stopped.get("status").asText());
    }

    @Test
    void runTaskRejectsMultipleCommands(@TempDir @NonNull Path tempDir) {
        RunTaskTool.Args args =
                new RunTaskTool.Args(
                        List.of(
                                new RunCommandTool.CommandInput("a", List.of()),
                                new RunCommandTool.CommandInput("b", List.of())),
                        false,
                        0);
        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () -> CapabilityTestCalls.execute(runTask, args));
        assertTrue(
                ToolErrors.normalize(error.getMessage()).contains("exactly one command"),
                "multi-command background must be rejected");
    }
}
