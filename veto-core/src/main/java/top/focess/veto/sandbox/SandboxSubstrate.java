package top.focess.veto.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * The process-execution substrate. It performs <b>no policy decisions</b>: path and command policy
 * belongs to the Gateway. The substrate receives an approved canonical working directory and owns
 * argv-only spawn, process lifecycle, environment reduction, timeouts, and available OS walls.
 *
 * <p>The only active implementation is {@link ConstrainedSubprocessSubstrate} (local, no
 * third-party runtime required).
 */
public sealed interface SandboxSubstrate permits ConstrainedSubprocessSubstrate {

    /**
     * Provision the sandbox for a session. Preflight-checks the runtime is reachable — fails fast,
     * no silent fallback to a weaker substrate.
     */
    @NonNull SandboxHandle provision(@NonNull SandboxProfile profile);

    /**
     * Run a {@code run_command} chain. Ordinary commands use argv[] direct exec; Windows command
     * shims use the constrained ComSpec file-format bridge. The substrate owns the wall (cwd lock,
     * environment projection, resource caps, network wall).
     */
    @NonNull CommandResult runCommands(
            @NonNull SandboxHandle h,
            @NonNull List<Command> cmds,
            @NonNull Path cwd,
            @NonNull ChainMode connect,
            @NonNull Duration timeout);

    /**
     * Start a single command as a detached background process - returns immediately with the
     * started {@link Process} (no stream draining, no {@code waitFor}). The caller owns lifecycle +
     * output draining. Stderr is merged into stdout ({@code redirectErrorStream}) so one drain
     * thread captures all output. Same env/cwd/kernel-wall setup as {@link #runCommands}; used by
     * {@code run_command(background=true)} for long-running servers (e.g. {@code npm run dev}) that
     * never exit and would otherwise block the turn.
     */
    @NonNull Process startBackground(
            @NonNull SandboxHandle h, @NonNull Command cmd, @NonNull Path cwd);

    /** Deprovision the sandbox, releasing runtime resources. */
    void deprovision(@NonNull SandboxHandle h);
}
