package top.focess.veto.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.api.process.ChainMode;
import top.focess.veto.api.process.Command;
import top.focess.veto.api.process.CommandResult;

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
    private static @NonNull Command sleeper() {
        return WINDOWS
                ? new Command("ping", List.of("-n", "20", "127.0.0.1"))
                : new Command("sleep", List.of("20"));
    }

    /** A command that exits fast with recognizable output. */
    private static @NonNull Command echoer(@NonNull String word) {
        return WINDOWS
                ? new Command("cmd", List.of("/c", "echo " + word))
                : new Command("echo", List.of(word));
    }

    private static @NonNull CommandResult run(
            @NonNull Path root,
            @NonNull List<@NonNull Command> commands,
            @NonNull ChainMode mode,
            @NonNull Duration timeout) {
        ConstrainedSubprocessSubstrate substrate = new ConstrainedSubprocessSubstrate();
        SandboxHandle handle = substrate.provision(SandboxProfile.defaults(root));
        try {
            return substrate.runCommands(handle, commands, Path.of("."), mode, timeout);
        } finally {
            substrate.deprovision(handle);
        }
    }

    private static @NonNull Command pipelineCommand(@NonNull String mode) throws Exception {
        var source = PipelineProcessProbe.class.getProtectionDomain().getCodeSource();
        if (source == null) throw new AssertionError("Pipeline probe has no class directory");
        var classes = Path.of(source.getLocation().toURI());
        return new Command(
                Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java")
                        .toString(),
                List.of("-cp", classes.toString(), PipelineProcessProbe.class.getName(), mode));
    }

    @Test
    void pipelineWaitsForUpstreamAfterConsumerExits(@TempDir @NonNull Path root) throws Exception {
        var result =
                run(
                        root,
                        List.of(pipelineCommand("producer"), pipelineCommand("consumer")),
                        ChainMode.PIPE,
                        Duration.ofSeconds(10));
        assertEquals(0, result.exitCode());
        assertEquals(List.of(0, 0), result.perCommand());
        assertTrue(result.stdout().contains("ready"));
    }

    @Test
    void pipelineDrainsUpstreamStderrBeforeItsStdout(@TempDir @NonNull Path root) throws Exception {
        var result =
                run(
                        root,
                        List.of(pipelineCommand("noisy"), pipelineCommand("consumer")),
                        ChainMode.PIPE,
                        Duration.ofSeconds(10));
        assertEquals(0, result.exitCode());
        // JVM startup diagnostics may precede the payload when JAVA_TOOL_OPTIONS is configured.
        assertTrue(requireStderr(result).contains("x".repeat(512 * 1024)));
        assertTrue(result.stdout().contains("ready"));
    }

    @Test
    void pipelineUsesOneDeadlineForAllMembers(@TempDir @NonNull Path root) throws Exception {
        var result =
                run(
                        root,
                        List.of(
                                pipelineCommand("delayed-producer"),
                                pipelineCommand("slow-consumer")),
                        ChainMode.PIPE,
                        Duration.ofSeconds(2));
        assertEquals(-1, result.exitCode());
        assertTrue(requireStderr(result).contains("[timeout]"));
    }

    @Test
    void runawayProcessIsKilledAtTheCap(@TempDir @NonNull Path root) {
        long start = System.nanoTime();
        CommandResult result =
                run(root, List.of(sleeper()), ChainMode.STOP_ON_FAILURE, Duration.ofSeconds(2));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(
                elapsedMs < 10_000,
                "cap of 2s should end the wait promptly, took " + elapsedMs + "ms");
        assertEquals(-1, result.exitCode());
        assertTrue(
                requireStderr(result).contains("[timeout]"),
                "result should carry the timeout marker");
    }

    @Test
    void fastCommandStillReturnsItsOutput(@TempDir @NonNull Path root) {
        CommandResult result =
                run(
                        root,
                        List.of(echoer("hello")),
                        ChainMode.STOP_ON_FAILURE,
                        Duration.ofSeconds(30));

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("hello"));
        assertFalse(requireStderr(result).contains("[timeout]"));
    }

    @Test
    void chainSharesOneDeadline(@TempDir @NonNull Path root) {
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
        assertTrue(requireStderr(result).contains("[timeout]"));
        assertFalse(
                result.stdout().contains("late"),
                "the second command must not run once the deadline is spent");
    }

    @Test
    void zeroTimeoutMeansNoCapButStillDrains(@TempDir @NonNull Path root) {
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
    void bareNameResolvesAndCodepageOutputDecodes(@TempDir @NonNull Path root) {
        Assumptions.assumeTrue(WINDOWS, "Windows-specific behavior");
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

    private static @NonNull String requireStderr(@NonNull CommandResult result) {
        String stderr = result.stderr();
        if (stderr != null) {
            return stderr;
        }
        throw new AssertionError("command result must include stderr text");
    }
}
