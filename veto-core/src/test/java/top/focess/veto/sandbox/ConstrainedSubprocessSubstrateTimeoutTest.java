package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The wall-clock cap must actually bound the blocking wait. Regression anchor: a {@code dir /b /s}
 * scan over three drive roots with {@code timeout=60} ran for 4+ minutes because the output streams
 * were read (blocking until process exit) BEFORE the capped wait — the cap was dead code. The drain
 * now runs alongside the capped wait, and a sequential chain gets ONE deadline for all its
 * commands.
 */
class ConstrainedSubprocessSubstrateTimeoutTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase().contains("win");

    /** A command that runs ~20s (well past every cap used here). */
    private static Command sleeper() {
        return WINDOWS
                ? new Command("ping", List.of("-n", "20", "127.0.0.1"))
                : new Command("sleep", List.of("20"));
    }

    /** A command that exits fast with recognizable output. */
    private static Command echoer(String word) {
        return WINDOWS
                ? new Command("cmd", List.of("/c", "echo " + word))
                : new Command("echo", List.of(word));
    }

    private static CommandResult run(
            Path root, List<Command> commands, ChainMode mode, Duration timeout) {
        ConstrainedSubprocessSubstrate substrate = new ConstrainedSubprocessSubstrate();
        SandboxHandle handle = substrate.provision(SandboxProfile.defaults(root));
        return substrate.runCommands(handle, commands, Path.of("."), mode, timeout);
    }

    @Test
    void runawayProcessIsKilledAtTheCap(@TempDir Path root) {
        long start = System.nanoTime();
        CommandResult result =
                run(root, List.of(sleeper()), ChainMode.STOP_ON_FAILURE, Duration.ofSeconds(2));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(
                elapsedMs < 10_000,
                "cap of 2s should end the wait promptly, took " + elapsedMs + "ms");
        assertEquals(-1, result.exitCode());
        assertTrue(result.stderr().contains("[timeout]"), "result should carry the timeout marker");
    }

    @Test
    void fastCommandStillReturnsItsOutput(@TempDir Path root) {
        CommandResult result =
                run(
                        root,
                        List.of(echoer("hello")),
                        ChainMode.STOP_ON_FAILURE,
                        Duration.ofSeconds(30));

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("hello"));
        assertFalse(result.stderr().contains("[timeout]"));
    }

    @Test
    void chainSharesOneDeadline(@TempDir Path root) {
        // The sleeper eats the whole 2s budget; the echo that follows must be cut off by the
        // shared deadline instead of getting its own 2s window.
        long start = System.nanoTime();
        CommandResult result =
                run(
                        root,
                        List.of(sleeper(), echoer("late")),
                        ChainMode.RUN_ALL,
                        Duration.ofSeconds(2));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(
                elapsedMs < 10_000,
                "chain should end at the shared deadline, took " + elapsedMs + "ms");
        assertEquals(-1, result.exitCode());
        assertTrue(result.stderr().contains("[timeout]"));
        assertFalse(
                result.stdout().contains("late"),
                "the second command must not run once the deadline is spent");
    }

    @Test
    void zeroTimeoutMeansNoCapButStillDrains(@TempDir Path root) {
        CommandResult result =
                run(root, List.of(echoer("unbounded")), ChainMode.STOP_ON_FAILURE, Duration.ZERO);

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("unbounded"));
    }

    /**
     * Two Windows regressions in one shot: a BARE extensionless name ({@code cmd}, really {@code
     * cmd.exe} via PATHEXT) must resolve — bare {@code npm} failing this way drove the agent to
     * hunt full paths — and the console-codepage (GBK) output must decode to real Chinese instead
     * of mojibake.
     */
    @Test
    void bareNameResolvesAndCodepageOutputDecodes(@TempDir Path root) {
        org.junit.jupiter.api.Assumptions.assumeTrue(WINDOWS, "Windows-specific behavior");
        CommandResult result =
                run(
                        root,
                        List.of(new Command("cmd", List.of("/c", "echo 中文输出"))),
                        ChainMode.STOP_ON_FAILURE,
                        Duration.ofSeconds(30));

        assertEquals(0, result.exitCode());
        assertTrue(
                result.stdout().contains("中文输出"),
                "console-codepage output must decode correctly, got: " + result.stdout());
    }
}
