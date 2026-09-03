package top.focess.veto.agent.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.mcp.ToolCallContext;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.llm.core.ToolResultPresentationMode;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.sandbox.Command;
import top.focess.veto.sandbox.SandboxManager;
import top.focess.veto.sandbox.TestSandboxFactory;

@EnabledOnOs(OS.WINDOWS)
@SuppressWarnings("initialization.field.uninitialized")
class InputTaskToolTest {

    private @NonNull BackgroundTaskManager manager;

    @BeforeEach
    void setUp() {
        manager =
                new BackgroundTaskManager(
                        new SandboxManager(TestSandboxFactory.uncontainedSubprocesses()));
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent-input",
                        UUID.randomUUID(),
                        null,
                        null,
                        UUID.randomUUID(),
                        ToolResultPresentationMode.BASIC,
                        ToolExecutionPermit.empty()));
    }

    @AfterEach
    void tearDown() {
        manager.stopAll("agent-input");
        manager.shutdown();
        ToolCallContextHolder.clear();
    }

    @Test
    void queuesInputAndClosesStdin(@TempDir @NonNull Path cwd) throws Exception {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null) throw new AssertionError("SystemRoot is unavailable");
        String executable =
                Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
                        .toString();
        BackgroundTaskManager.TaskInfo task =
                manager.start(
                        "agent-input",
                        new Command(
                                executable,
                                List.of(
                                        "-NoProfile",
                                        "-Command",
                                        "$line=[Console]::In.ReadLine(); [Console]::Out.WriteLine('got:'+$line)")),
                        cwd,
                        30,
                        null);

        String response =
                new InputTaskTool(manager)
                        .execute(new InputTaskTool.Args(task.taskId(), "hello", true, true));
        JsonNode result = new ObjectMapper().readTree(response);
        assertEquals("queued", result.get("status").asText());
        assertEquals(6, result.get("bytes").asInt());
        assertTrue(result.get("closeQueued").asBoolean());

        String output = "";
        for (int attempt = 0; attempt < 100; attempt++) {
            output = manager.output("agent-input", task.taskId(), 10).orElse("");
            if (output.contains("got:hello")) break;
            Thread.sleep(25);
        }
        assertTrue(output.contains("got:hello"));
        for (int attempt = 0; attempt < 100; attempt++) {
            if (!manager.status("agent-input", task.taskId()).orElseThrow().alive()) break;
            Thread.sleep(25);
        }
        assertFalse(manager.status("agent-input", task.taskId()).orElseThrow().alive());
    }
}
