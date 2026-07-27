package top.focess.veto.agent.mcp.tools;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolSecurity;
import top.focess.veto.sandbox.SandboxSubstrate;

/**
 * {@code run_command} — the special tool that executes arbitrary external processes.
 *
 * <p>This tool is registered as a {@link NativeTool} so its schema is advertised in the manifest,
 * but its execution does <b>not</b> run a process in the host JVM: {@link
 * top.focess.veto.agent.mcp.ToolEngine#execute} special-cases {@code run_command} and routes it
 * through the session's {@link SandboxSubstrate} (no shell, argv[] direct exec, cwd locked,
 * Veto-controlled chaining). Consequently {@link #execute} is never invoked by the engine and
 * throws to make the special-casing explicit.
 */
@Component
@ToolSecurity(risk = RiskCategory.SHELL_EXEC)
public final class RunCommandTool implements NativeTool<RunCommandTool.Args> {

    /** A single discrete command in the chain. */
    public record CommandInput(
            @Doc(
                            "Binary name (resolved against the exec allowlist), e.g. 'gradle'. Not a shell string.")
                    String executable,
            @Doc("argv array. Glob/env expansion is done by Veto, not a shell.")
                    List<String> args) {}

    @ToolDoc(
            description =
                    "Run one or more commands inside the sandbox. The model lists discrete commands; "
                            + "Veto connects them per `connect`.",
            usage =
                    """
                    #### When to use
                    Use `run_command` to execute one or more discrete external programs inside the sandbox - \
                    building the project (`gradle build`), running tests, invoking a code generator, or querying \
                    a tool whose CLI you need. You list each command as a separate `{executable, args}` entry; \
                    Veto connects them per `connect`. This is the only tool that touches the host process layer.

                    #### When NOT to use
                    - Do not use `run_command` to read or edit files - use `view_file` / `write_to_file` / \
                    `replace_file_content`.
                    - Do not use it to search text - use `grep_search`.
                    - Do not pass a shell string with operators (`&&`, `|`, `>`); there is no shell. Express each \
                    step as its own command entry and let `connect` wire them.
                    - Do not reach for it for trivially in-memory work the model can do directly.

                    #### Behavior
                    Each `commands` entry is `{executable, args}` where `executable` is a bare binary name \
                    resolved against the exec allowlist (e.g. "gradle", "git") and `args` is an argv array of \
                    literal strings - no shell, no shell-driven glob/env expansion (Veto performs any expansion \
                    itself). Execution does NOT run in the host JVM: ToolEngine special-cases `run_command` and \
                    routes it through the session's SandboxSubstrate - direct argv[] exec, cwd locked to `cwd`, \
                    no shell, Veto-controlled chaining. The `connect` mode decides how entries relate: \
                    `STOP_ON_FAILURE` (default) runs them in order and halts at the first non-zero exit; `RUN_ALL` \
                    runs every entry regardless of failures; `PIPE` feeds one entry's stdout into the next \
                    entry's stdin.

                    #### Return format
                    The sandbox substrate returns the combined output (stdout + stderr per the substrate's \
                    policy) and per-command exit statuses as a structured observation. The exact envelope is \
                    produced by the substrate, not by this tool's `execute` (which is never called - it throws \
                    to make the special-casing explicit).

                    #### Errors & edge cases
                    - `executable` not on the allowlist -> the call is rejected before execution.
                    - `cwd` outside an allowed root -> the Gateway blocks the call.
                    - A command exits non-zero under `STOP_ON_FAILURE` -> the chain halts; later entries do not \
                    run.
                    - `connect` omitted -> defaults to `STOP_ON_FAILURE`.
                    - argv entries are literal; a `*` or `$HOME` in an arg is passed through verbatim unless Veto \
                    expands it - do not rely on shell semantics.

                    #### Security
                    `commands` carries SHELL_COMMAND and `cwd` carries FILESYSTEM_PATH: both are screened by the \
                    Gateway (path check + semantic screening) before execution. The operation is \
                    `RiskCategory.SHELL_EXEC` - the highest-risk category, always audited and may require human \
                    approval. There is no shell, so shell-injection is structurally impossible; the risk is the \
                    command itself. Prefer the narrowest `cwd` and the fewest entries that accomplish the goal. \
                    Do not attempt to chain around the sandbox - the substrate enforces the exec allowlist and \
                    cwd lock.
                    """,
            examples = {
                "{\"commands\": [{\"executable\": \"gradle\", \"args\": [\"build\"]}], \"cwd\": \"/abs\", \"connect\": \"STOP_ON_FAILURE\"}",
                "{\"commands\": [{\"executable\": \"gradle\", \"args\": [\"test\"]}], \"cwd\": \"/abs\"}",
                "{\"commands\": [{\"executable\": \"git\", \"args\": [\"status\"]}], \"cwd\": \"/abs\"}",
                "{\"commands\": [{\"executable\": \"git\", \"args\": [\"log\", \"--oneline\", \"-10\"]}], \"cwd\": \"/abs\"}",
                "{\"commands\": [{\"executable\": \"gradle\", \"args\": [\"build\"]}, {\"executable\": \"gradle\", \"args\": [\"test\"]}], \"cwd\": \"/abs\", \"connect\": \"STOP_ON_FAILURE\"}",
                "{\"commands\": [{\"executable\": \"grep\", \"args\": [\"-r\", \"TODO\", \"src\"]}, {\"executable\": \"wc\", \"args\": [\"-l\"]}], \"cwd\": \"/abs\", \"connect\": \"PIPE\"}",
                "{\"commands\": [{\"executable\": \"gradle\", \"args\": [\"clean\", \"build\", \"test\"]}], \"cwd\": \"/abs\", \"connect\": \"RUN_ALL\"}",
                "{\"commands\": [{\"executable\": \"node\", \"args\": [\"script.js\"]}], \"cwd\": \"/abs/app\"}"
            })
    public record Args(
            @SecurityHint(ParamCategory.SHELL_COMMAND)
                    @Doc(
                            "Discrete commands; Veto connects them per `connect`. No shell, no chaining operators in input.")
                    List<CommandInput> commands,
            @SecurityHint(ParamCategory.FILESYSTEM_PATH)
                    @Doc("Working directory; must be under an allowed root (Gateway-checked).")
                    String cwd,
            @Doc(
                            /* annotation was: @Nullable */
                            "How Veto connects the commands: STOP_ON_FAILURE (default), RUN_ALL, or PIPE.")
                    String connect) {}

    @Override
    public String getName() {
        return "run_command";
    }

    @Override
    public String getDescription() {
        return "Run one or more commands inside the sandbox. The model lists discrete commands; "
                + "Veto connects them per `connect`.";
    }

    @Override
    public Class<Args> getArgsClass() {
        return Args.class;
    }

    @Override
    public @NonNull String execute(@NonNull Args args) {
        throw new UnsupportedOperationException(
                "run_command execution is routed through the Sandbox substrate by ToolEngine.execute, "
                        + "not through NativeTool.execute.");
    }
}
