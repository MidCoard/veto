package top.focess.veto.agent.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolErrors;
import top.focess.veto.agent.mcp.ToolExecutionException;
import top.focess.veto.agent.screening.DeployerPolicy;
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
        runTask = new RunTaskTool(manager, mapper);
        status = new ViewTaskTool(manager, mapper);
        stop = new StopTaskTool(manager, mapper);
        ToolCallContextHolder.set("agent-x", UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        ToolCallContextHolder.clear();
        manager.stopAll("agent-x");
        manager.shutdown();
    }

    @Test
    void runTaskLaunchesAndStatusReportsExit(@TempDir @NonNull Path tempDir) throws Exception {
        ToolExecutionPermit permit =
                new ToolExecutionPermit(
                        "run_task",
                        Map.of(),
                        Map.of(
                                "cwd",
                                new ToolExecutionPermit.AuthorizedPath(
                                        "cwd", tempDir.toString(), tempDir, 0, true)),
                        List.of(tempDir),
                        DeployerPolicy.FULL_ACCESS,
                        Set.of());
        ToolCallContextHolder.set("agent-x", UUID.randomUUID(), null, null, null, permit);
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        String exe =
                Path.of(System.getProperty("java.home"), "bin", win ? "java.exe" : "java")
                        .toString();
        RunTaskTool.Args args =
                new RunTaskTool.Args(
                        List.of(new RunCommandTool.CommandInput(exe, List.of("-version"))),
                        tempDir.toString(),
                        false,
                        0);

        String startedJson = runTask.execute(args);
        JsonNode started = mapper.readTree(startedJson);
        assertEquals("started", started.get("status").asText());
        String taskId = started.get("taskId").asText();
        assertFalse(taskId.isBlank());

        // Wait for the quick-exit task to finish, polling view_task.
        JsonNode statusNode = null;
        for (int i = 0; i < 200; i++) {
            statusNode = mapper.readTree(status.execute(new ViewTaskTool.Args(taskId, 50)));
            if (!statusNode.get("alive").asBoolean()) break;
            Thread.sleep(50);
        }
        if (statusNode == null) throw new AssertionError("missing task status");
        assertFalse(statusNode.get("alive").asBoolean(), "task should have exited");
        assertEquals(0, statusNode.get("exitCode").asInt(), "java -version exits 0");
        assertFalse(statusNode.get("recentOutput").asText().isBlank(), "output captured");

        // stop_task is idempotent on an already-exited task.
        JsonNode stopped = mapper.readTree(stop.execute(new StopTaskTool.Args(taskId)));
        assertEquals("stopped", stopped.get("status").asText());
    }

    @Test
    void runTaskRejectsMultipleCommands(@TempDir @NonNull Path tempDir) {
        RunTaskTool.Args args =
                new RunTaskTool.Args(
                        List.of(
                                new RunCommandTool.CommandInput("a", List.of()),
                                new RunCommandTool.CommandInput("b", List.of())),
                        tempDir.toString(),
                        false,
                        0);
        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () -> runTask.execute(args));
        assertTrue(
                ToolErrors.normalize(error.getMessage()).contains("exactly one command"),
                "multi-command background must be rejected");
    }

    @Test
    void runTaskRequiresExplicitTimeout(@TempDir @NonNull Path tempDir) throws Exception {
        RunTaskTool.Args args =
                mapper.readValue(
                        "{\"commands\":[{\"executable\":\"a\",\"args\":[]}],\"cwd\":\""
                                + tempDir.toString().replace("\\", "\\\\")
                                + "\"}",
                        ToolDocs.nonNullClass(RunTaskTool.Args.class));
        ToolExecutionException error =
                assertThrows(
                        ToolDocs.nonNullClass(ToolExecutionException.class),
                        () -> runTask.execute(args));
        assertTrue(
                ToolErrors.normalize(error.getMessage()).contains("timeout"),
                "null timeout must be rejected");
    }
}
