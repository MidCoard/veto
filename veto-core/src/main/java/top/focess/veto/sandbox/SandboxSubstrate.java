package top.focess.veto.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The hard backstop containment boundary and execution substrate. The Sandbox performs <b>no policy
 * decisions</b> — path/command/network confinement <i>policy</i> belongs to the Gateway's {@code
 * PolicyProfile}. The Sandbox only enforces a blast-radius floor and runs commands inside it.
 * File-op syscalls route through the substrate so file ops share the hard wall.
 *
 * <p>The only active implementation is {@link ConstrainedSubprocessSubstrate} (local, no
 * third-party runtime required).
 */
public sealed interface SandboxSubstrate permits ConstrainedSubprocessSubstrate {

    /**
     * Provision the sandbox for a session. Preflight-checks the runtime is reachable — fails fast,
     * no silent fallback to a weaker substrate.
     */
    SandboxHandle provision(SandboxProfile profile);

    /**
     * Run a {@code run_command} chain. NO SHELL — argv[] direct exec. Substrate owns the wall (cwd
     * lock, env sanitization, resource caps, network wall).
     */
    CommandResult runCommands(
            SandboxHandle h, List<Command> cmds, Path cwd, ChainMode connect, Duration timeout);

    /** {@code view_file} — read bytes relative to the workspace root. */
    byte[] readFile(SandboxHandle h, Path rel);

    /** {@code write_to_file} — write bytes relative to the workspace root. */
    void writeFile(SandboxHandle h, Path rel, byte[] content);

    /** {@code replace_file_content} — apply a contiguous patch relative to the workspace root. */
    void patchFile(SandboxHandle h, Path rel, PatchSpec patch);

    /** {@code list_dir} — list entries relative to the workspace root. */
    List<Entry> listDir(SandboxHandle h, Path rel);

    /** {@code grep_search} — search under a path relative to the workspace root. */
    List<Match> grep(SandboxHandle h, Path rel, GrepSpec spec);

    /** Stat a path relative to the workspace root (ReadHistory snapshots). */
    Stat stat(SandboxHandle h, Path rel);

    /** Deprovision the sandbox, releasing runtime resources. */
    void deprovision(SandboxHandle h);
}
