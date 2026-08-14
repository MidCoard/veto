package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Validates {@link BackgroundTaskManager}: non-blocking start, exit detection + output capture,
 * stop, per-agent isolation, and stopAll cleanup. Uses the JDK's own {@code java} binary (no shell)
 * for a quick-exit command and {@code ping}/{@code sleep} for a long-running one.
 */
class BackgroundTaskManagerTest {

    private static @NonNull BackgroundTaskManager newManager() {
        return new BackgroundTaskManager(new SandboxManager(new ConstrainedSubprocessSubstrate()));
    }

    private static @NonNull Command javaVersion() {
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        String exe =
                Path.of(System.getProperty("java.home"), "bin", win ? "java.exe" : "java")
                        .toString();
        return new Command(exe, java.util.List.of("-version"));
    }

    private static @NonNull Command longRunning() {
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        return win
                ? new Command("ping", java.util.List.of("-n", "60", "127.0.0.1"))
                : new Command("sleep", java.util.List.of("60"));
    }

    @Test
    void startReturnsImmediatelyAndCapturesExit(
            @TempDir @org.jspecify.annotations.NonNull Path tempDir) {
        BackgroundTaskManager mgr = newManager();
        BackgroundTaskManager.TaskInfo info = mgr.start("agent-a", javaVersion(), tempDir, 0, null);
        assertEquals("agent-a", info.agentId());
        assertNotNull(info.taskId());
        assertTrue(info.pid() > 0);

        assertTrue(waitForExit(mgr, "agent-a", info.taskId(), Duration.ofSeconds(20)));
        Optional<BackgroundTaskManager.TaskInfo> status = mgr.status("agent-a", info.taskId());
        assertTrue(status.isPresent());
        assertFalse(status.get().alive(), "task should be done");
        assertEquals(0, requireExitCode(status.get().exitCode()), "java -version exits 0");

        Optional<String> out = mgr.output("agent-a", info.taskId(), 50);
        assertTrue(out.isPresent());
        assertFalse(out.get().isBlank(), "version output captured");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void stopKillsRunningTaskWindows(@TempDir @org.jspecify.annotations.NonNull Path tempDir) {
        stopKillsRunningTask(tempDir);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void stopKillsRunningTaskPosix(@TempDir @org.jspecify.annotations.NonNull Path tempDir) {
        stopKillsRunningTask(tempDir);
    }

    private void stopKillsRunningTask(@NonNull Path tempDir) {
        BackgroundTaskManager mgr = newManager();
        BackgroundTaskManager.TaskInfo info = mgr.start("agent-b", longRunning(), tempDir, 0, null);
        assertTrue(info.alive(), "long-running task is alive right after start");
        Optional<BackgroundTaskManager.TaskInfo> stopped =
                mgr.stop("agent-b", info.taskId(), BackgroundTaskManager.ExitCause.AGENT_STOP);
        assertTrue(stopped.isPresent());
        assertTrue(waitForExit(mgr, "agent-b", info.taskId(), Duration.ofSeconds(5)));
    }

    @Test
    void exitNoticesCarryTheCause(@TempDir @org.jspecify.annotations.NonNull Path tempDir) {
        BackgroundTaskManager mgr = newManager();
        // Natural exit → NATURAL.
        BackgroundTaskManager.TaskInfo natural =
                mgr.start("agent-n", javaVersion(), tempDir, 0, null);
        assertTrue(waitForExit(mgr, "agent-n", natural.taskId(), Duration.ofSeconds(20)));
        BackgroundTaskManager.TaskExitNotice naturalNotice =
                waitForNotice(mgr, "agent-n", Duration.ofSeconds(5));
        assertEquals(BackgroundTaskManager.ExitCause.NATURAL, naturalNotice.cause());
        assertEquals(0, naturalNotice.exitCode());

        // Explicit stop → the caller's cause rides the notice.
        BackgroundTaskManager.TaskInfo stopped =
                mgr.start("agent-n", longRunning(), tempDir, 0, null);
        mgr.stop("agent-n", stopped.taskId(), BackgroundTaskManager.ExitCause.USER_STOP);
        assertTrue(waitForExit(mgr, "agent-n", stopped.taskId(), Duration.ofSeconds(5)));
        BackgroundTaskManager.TaskExitNotice stopNotice =
                waitForNotice(mgr, "agent-n", Duration.ofSeconds(5));
        assertEquals(BackgroundTaskManager.ExitCause.USER_STOP, stopNotice.cause());
    }

    @Test
    void perAgentIsolation(@TempDir @org.jspecify.annotations.NonNull Path tempDir) {
        BackgroundTaskManager mgr = newManager();
        BackgroundTaskManager.TaskInfo a = mgr.start("agent-a", longRunning(), tempDir, 0, null);
        // agent-b cannot see or stop agent-a's task.
        assertTrue(mgr.status("agent-b", a.taskId()).isEmpty());
        assertTrue(
                mgr.stop("agent-b", a.taskId(), BackgroundTaskManager.ExitCause.AGENT_STOP)
                        .isEmpty());
        assertEquals(0, mgr.list("agent-b").size());
        assertEquals(1, mgr.list("agent-a").size());
        mgr.stopAll("agent-a");
    }

    @Test
    void stopAllKillsEveryOwnedTask(@TempDir @org.jspecify.annotations.NonNull Path tempDir) {
        BackgroundTaskManager mgr = newManager();
        BackgroundTaskManager.TaskInfo t1 = mgr.start("agent-c", longRunning(), tempDir, 0, null);
        BackgroundTaskManager.TaskInfo t2 = mgr.start("agent-c", longRunning(), tempDir, 0, null);
        assertEquals(2, mgr.list("agent-c").size());
        mgr.stopAll("agent-c");
        assertTrue(waitForExit(mgr, "agent-c", t1.taskId(), Duration.ofSeconds(5)));
        assertTrue(waitForExit(mgr, "agent-c", t2.taskId(), Duration.ofSeconds(5)));
    }

    /** Polls until one exit notice is queued for the agent; fails the test on timeout. */
    private static BackgroundTaskManager.@NonNull TaskExitNotice waitForNotice(
            @NonNull BackgroundTaskManager mgr,
            @NonNull String agentId,
            @NonNull Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            java.util.List<BackgroundTaskManager.TaskExitNotice> notices =
                    mgr.drainExitNotices(agentId);
            if (!notices.isEmpty()) {
                return notices.get(0);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("no exit notice queued for " + agentId + " within " + timeout);
        throw new IllegalStateException("unreachable");
    }

    /** Polls until the task is no longer alive; {@code false} on timeout. */
    private static boolean waitForExit(
            @NonNull BackgroundTaskManager mgr,
            @NonNull String agentId,
            @NonNull String taskId,
            @NonNull Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Optional<BackgroundTaskManager.TaskInfo> s = mgr.status(agentId, taskId);
            if (s.isPresent() && !s.get().alive()) return true;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static int requireExitCode(Integer exitCode) {
        if (exitCode != null) {
            return exitCode;
        }
        throw new AssertionError("completed task must have an exit code");
    }
}
