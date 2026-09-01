package top.focess.veto.agent.mcp.tools;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolCapability;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolResultFormat;
import top.focess.veto.agent.mcp.ToolSecurity;
import top.focess.veto.sandbox.ChainMode;
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
@ToolSecurity(risk = RiskCategory.SHELL_EXEC, capability = ToolCapability.PROCESS_EXECUTION)
public final class RunCommandTool implements NativeTool<RunCommandTool.Args> {

    /** A single discrete command in the chain. */
    public record CommandInput(
            @Doc(
                            "Binary name resolved by the sandbox using the operating system's executable lookup rules, e.g. 'gradle'. Not a shell string.")
                    @NonNull String executable,
            @Doc(
                            "Literal argv array. Veto and the process launcher do not expand globs or environment variables.")
                    @NonNull List<String> args) {}

    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description =
                    "Run one or more commands inside the sandbox. The model lists discrete commands; "
                            + "Veto connects them per `connect`.",
            behavior =
                    """
                    Each `commands` entry is `{executable, args}` where `executable` is a binary name or path \
                    classified by the Gateway and resolved by the sandbox, and `args` is an argv array of \
                    literal strings; neither Veto nor a shell expands globs or environment variables. ToolEngine \
                    routes execution through the session's SandboxSubstrate with a fixed `cwd` and \
                    Veto-controlled chaining. Ordinary executables use direct argv execution. On Windows, \
                    `.cmd`/`.bat` launchers use a restricted `ComSpec` bridge because CreateProcess cannot execute \
                    those formats directly; interpreter metacharacters are rejected. The `connect` mode decides \
                    how entries relate: \
                    `STOP_ON_FAILURE` (default) runs them in order and halts at the first non-zero exit; `RUN_ALL` \
                    runs every entry regardless of failures; `PIPE` feeds one entry's stdout into the next \
                    entry's stdin. `timeout` in seconds (0 = sandbox-profile maximum) bounds the blocking wait - a \
                    timed-out chain is forcibly killed and the result carries `[timeout]`. For a long-running \
                    server that never exits (e.g. `npm run dev`), use `run_task` instead so the call does not \
                    block the turn.
                    """,
            whenToUse =
                    """
                    Use `run_command` to execute one or more discrete external programs inside the sandbox - \
                    building the project (`gradle build`), running tests, invoking a code generator, or querying \
                    a tool whose CLI you need. You list each command as a separate `{executable, args}` entry; \
                    Veto connects them per `connect`.
                    """,
            whenNotToUse =
                    """
                    - Do not use `run_command` to read or edit files - use `view_file` / `write_to_file` / \
                    `replace_file_content`.
                    - Do not use it to search text - use `grep_search`.
                    - Do not pass a shell string with operators (`&&`, `|`, `>`); there is no shell. Express each \
                    step as its own command entry and let `connect` wire them.
                    - Do not reach for it for trivially in-memory work the model can do directly.
                    """,
            resultContract =
                    """
                    Plain text containing stdout, followed by a `[stderr]` section when stderr is present. \
                    A non-zero final command/stage status appends `(exit code: N)` and marks the tool result as \
                    failed. `RUN_ALL` and `PIPE` use the last command/stage as the overall status. Timeout output \
                    contains `[timeout]` and exit code -1. A successful command with no output returns empty text.
                    """,
            errorsAndEdgeCases =
                    """
                    - A policy refusal means no process started. Revise the request or obtain the applicable \
                    approval; do not retry the unchanged call.
                    - The working directory is the session-selected workspace root and cannot be overridden by the call.
                    - A negative `timeout` or an empty supplied `commands` array is rejected before a process starts.
                    - If an executable cannot be resolved or is not installed, select the project's wrapper or \
                    an observed executable path rather than guessing repeatedly.
                    """,
            security =
                    """
                    `commands` carries SHELL_COMMAND and is screened by the Gateway before execution. The working \
                    directory is bound to the session-selected workspace root in the screened execution permit. The operation is \
                    `RiskCategory.SHELL_EXEC` - the highest-risk category, always audited and may require human \
                    approval. Ordinary executables are direct argv launches. Windows `.cmd`/`.bat` shims use \
                    the OS `ComSpec` interpreter because CreateProcess cannot execute that file format directly; \
                    Veto rejects interpreter metacharacters in shim arguments. Prefer \
                    the fewest entries that accomplish the goal. \
                    Do not attempt to chain around the sandbox - argv separation and the authorized cwd remain \
                    enforced after approval.
                    """,
            examples = {
                "{\"commands\": [{\"executable\": \"gradle\", \"args\": [\"build\"]}], \"connect\": \"STOP_ON_FAILURE\", \"timeout\": 300}",
                "{\"commands\": [{\"executable\": \"gradle\", \"args\": [\"test\"]}], \"timeout\": 300}",
                "{\"commands\": [{\"executable\": \"git\", \"args\": [\"status\"]}], \"timeout\": 60}",
                "{\"commands\": [{\"executable\": \"git\", \"args\": [\"log\", \"--oneline\", \"-10\"]}], \"timeout\": 60}",
                "{\"commands\": [{\"executable\": \"gradle\", \"args\": [\"build\"]}, {\"executable\": \"gradle\", \"args\": [\"test\"]}], \"connect\": \"STOP_ON_FAILURE\", \"timeout\": 600}",
                "{\"commands\": [{\"executable\": \"grep\", \"args\": [\"-r\", \"TODO\", \"src\"]}, {\"executable\": \"wc\", \"args\": [\"-l\"]}], \"connect\": \"PIPE\", \"timeout\": 120}",
                "{\"commands\": [{\"executable\": \"gradle\", \"args\": [\"clean\", \"build\", \"test\"]}], \"connect\": \"RUN_ALL\", \"timeout\": 900}",
                "{\"commands\": [{\"executable\": \"node\", \"args\": [\"script.js\"]}], \"timeout\": 120}"
            },
            returnExamples = {"BUILD SUCCESSFUL in 12s"})
    public record Args(
            @SecurityHint(ParamCategory.SHELL_COMMAND)
                    @Doc(
                            "Discrete commands; Veto connects them per `connect`. No shell, no chaining operators in input.")
                    @NonNull List<CommandInput> commands,
            @Doc("How Veto connects the commands: STOP_ON_FAILURE (default), RUN_ALL, or PIPE.")
                    ChainMode connect,
            @Doc(
                            "Request network access for this execution. Defaults to false; true is separately Gateway-screened.")
                    Boolean network,
            @NonNull
                    @Doc(
                            "Timeout in seconds. 0 selects the sandbox-profile maximum; larger values are capped"
                                    + " by that maximum.")
                    Integer timeout) {

        /** Compatibility constructor for callers that accept the default deny-network posture. */
        public Args(
                @NonNull List<CommandInput> commands, ChainMode connect, @NonNull Integer timeout) {
            this(commands, connect, false, timeout);
        }
    }

    @Override
    public @NonNull String getName() {
        return "run_command";
    }

    @Override
    public @NonNull String getDescription() {
        return "Run one or more commands inside the sandbox. The model lists discrete commands; "
                + "Veto connects them per `connect`.";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(@NonNull Args args) {
        throw new UnsupportedOperationException(
                "run_command execution is routed through the Sandbox substrate by ToolEngine.execute, "
                        + "not through NativeTool.execute.");
    }
}
