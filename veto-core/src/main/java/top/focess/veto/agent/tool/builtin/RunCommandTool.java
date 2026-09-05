package top.focess.veto.agent.tool.builtin;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.capability.ProcessExecutionCapability;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.tool.Doc;
import top.focess.veto.agent.tool.ParamCategory;
import top.focess.veto.agent.tool.ProcessExecutionTool;
import top.focess.veto.agent.tool.SecurityHint;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDoc;
import top.focess.veto.agent.tool.ToolDocs;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.ToolSecurity;
import top.focess.veto.sandbox.ChainMode;

/** Executes screened commands through the sandbox using the standard native-tool path. */
@Component
@ToolSecurity(capability = ToolCapability.PROCESS_EXECUTION, defaultDanger = Danger.ELEVATED)
public final class RunCommandTool implements ProcessExecutionTool<RunCommandTool.Args> {
    private final @NonNull ProcessExecutionCapability capability;

    public RunCommandTool(@NonNull ProcessExecutionCapability capability) {
        this.capability = capability;
    }

    /** A single discrete command in the chain. */
    public record CommandInput(
            @Doc(
                            "Binary name resolved by the sandbox using the operating system's executable lookup rules, e.g. 'gradle'. Not a shell string.")
                    @NonNull String executable,
            @Doc(
                            "Literal argv array. Veto and the process launcher do not expand globs or environment variables.")
                    @NonNull List<@NonNull String> args) {}

    @ToolDoc(
            resultFormats = {ToolResultFormat.PLAINTEXT},
            description =
                    "Run one or more commands inside the sandbox. The model lists discrete commands; "
                            + "Veto connects them per `connect`.",
            behavior =
                    """
                    Each `commands` entry is `{executable, args}` where `executable` is a binary name or path \
                    resolved for the execution environment, and `args` is an argv array of \
                    literal strings. Each executable is spawned directly; there is no shell. Shell operators \
                    (`&&`, `||`, `;`), redirections (`>`, `>>`), shell pipes, glob expansion (`*`), and variable \
                    expansion (`%VAR%` or `$VAR`) do not work. Put sequential commands in separate `commands` \
                    entries and select their relationship with `connect`; use `PIPE` to pass stdout to the next \
                    command's stdin. The working directory is the session workspace root. Windows command-script \
                    launchers are supported, but shell metacharacters in \
                    their arguments are rejected. The `connect` mode decides \
                    how entries relate: \
                    `STOP_ON_FAILURE` (default) runs them in order and halts at the first non-zero exit; `RUN_ALL` \
                    runs every entry regardless of failures; `PIPE` feeds one entry's stdout into the next \
                    entry's stdin. `timeout` in seconds (0 selects the configured maximum) bounds the blocking wait - a \
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
                    - A negative `timeout` or an empty supplied `commands` array is rejected before a process starts.
                    - If an executable cannot be resolved or is not installed, select the project's wrapper or \
                    an observed executable path rather than guessing repeatedly.
                    """,
            security =
                    "The working directory is the session workspace root. Execution and requested network access may require approval. Approval does not expand filesystem access or permit combining executable and arguments.",
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
                    @NonNull List<@NonNull CommandInput> commands,
            @Doc("How Veto connects the commands: STOP_ON_FAILURE (default), RUN_ALL, or PIPE.")
                    ChainMode connect,
            @Doc(
                            "Request network access for this execution. Defaults to false; true may require approval.")
                    Boolean network,
            @NonNull
                    @Doc(
                            "Timeout in seconds. 0 selects the configured maximum; larger values are capped"
                                    + " by that maximum.")
                    Integer timeout) {

        /** Compatibility constructor for callers that accept the default deny-network posture. */
        public Args(
                @NonNull List<@NonNull CommandInput> commands,
                ChainMode connect,
                @NonNull Integer timeout) {
            this(commands, connect, false, timeout);
        }
    }

    @Override
    public @NonNull String getName() {
        return "run_command";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull ProcessExecutionCapability processExecutionCapability() {
        return capability;
    }

    @Override
    public @NonNull String execute(
            @NonNull Args args, @NonNull ProcessExecutionCapability capability) {
        return capability.runCommand(args);
    }
}
