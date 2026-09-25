package top.focess.veto.builtin.tools;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.Doc;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.PreparedTool;
import top.focess.veto.api.agent.tool.SecurityHint;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDoc;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolErrorCode;
import top.focess.veto.api.agent.tool.ToolErrors;
import top.focess.veto.api.agent.tool.ToolJson;
import top.focess.veto.api.agent.tool.ToolPreparation;
import top.focess.veto.api.agent.tool.ToolResultFormat;
import top.focess.veto.api.agent.tool.ToolSecurity;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.process.ChainMode;
import top.focess.veto.api.process.Command;
import top.focess.veto.builtin.process.ProcessExecutionCapability;
import top.focess.veto.builtin.process.TaskInfo;

/**
 * {@code run_task} - launch a long-running command as a detached background task. Takes the same
 * command shape as {@code run_command} but does <b>not</b> block: the process is started by the
 * sandbox substrate and drained/tracked by the host background-task service, and the call returns
 * immediately with a {@code taskId}. Use this for servers/watchers/daemons that never exit (e.g.
 * {@code npm run dev}); use {@code run_command} for commands whose result you need inline.
 *
 * <p>Background = a single command (no chaining); multiple commands are rejected. {@code timeout}
 * (seconds, required; {@code 0} selects the sandbox-profile maximum) is the requested lifetime; the
 * effective lifetime never exceeds the profile maximum. Follow up with {@code view_task} (status +
 * recent output) and {@code stop_task}. When a task ends, the agent is told about it on its next
 * turn.
 */
@ToolSecurity(capability = ToolCapability.PROCESS_EXECUTION, defaultDanger = Danger.ELEVATED)
@ToolDoc(
        resultFormats = {ToolResultFormat.JSON},
        description =
                "Launch a long-running command as a detached background task (non-blocking). Returns a taskId immediately; the process keeps running across turns.",
        behavior =
                """
                Starts `commands[0]` from the session workspace using `run_command` direct-execution rules. \
                Merged stdout/stderr is captured for `view_task`. `timeout` bounds total lifetime \
                (0 selects the configured maximum); expiration stops the process. \
                When only the completed result is needed, call `view_task` once with `waitForExit=true`, not repeated \
                status calls. This keeps the assignment open until its result is available. Read output \
                once if needed. Use `view_task` for concrete progress/interaction needs and `stop_task` \
                to end it. Started does not mean completed.
                """,
        whenToUse =
                """
                Use `run_task` for a long-running process that you want running while you keep \
                working - a dev server (`npm run dev`), a file watcher, \
                a long build you will check later. The call returns at once with a `taskId`; \
                the process survives across turns and its output is captured for you.
                """,
        whenNotToUse =
                """
                - Do not use it for a command whose result you need immediately - use \
                `run_command` (blocking) instead.
                - Do not launch more than one command per call - background mode does not \
                chain; express a pipeline as separate steps.
                - Do not stop a task you launched with an OS kill command (`taskkill` / `kill`) \
                - use `stop_task` with its `taskId`. That is the only sanctioned stop path and \
                it keeps task status and final output available to `view_task`.
                """,
        resultContract =
                """
                A JSON outcome: `{"status":"started","taskId":"bg-3","pid":1234, \
                "command":"npm run dev","cwd":"...","requestedTimeoutSeconds":0, \
                "effectiveTimeoutSeconds":600}` plus `nextStep` waiting guidance. Invalid \
                arguments (failure, INVALID_ARGUMENTS): \
                `Invalid arguments: timeout must be zero or positive.` or \
                `Invalid arguments: exactly one command is required (background mode does not chain); got 2.`
                """,
        errorsAndEdgeCases =
                """
                - Any supplied command count other than one -> rejected (background mode does not chain).
                - A negative supplied `timeout` -> rejected.
                - Only the latest 5000 output lines are retained; an unterminated line is capped at 65536 bytes.
                """,
        security =
                "The task runs with the same direct-execution rules as run_command: no shell, and executable and args cannot be combined to smuggle flags. Background execution does not grant additional file or network access.",
        examples = {
            "{\"commands\": [{\"executable\": \"npm\", \"args\": [\"run\", \"dev\"]}], \"timeout\": 0}",
            "{\"commands\": [{\"executable\": \"gradle\", \"args\": [\"build\"]}], \"timeout\": 1200}",
            "{\"commands\": [{\"executable\": \"gradle\", \"args\": [\"test\", \"--continuous\"]}], \"timeout\": 1800}",
            "{\"commands\": [{\"executable\": \"python\", \"args\": [\"-m\", \"http.server\", \"8000\"]}], \"network\": true, \"timeout\": 3600}",
            "{\"commands\": [{\"executable\": \"gradle\", \"args\": [\"build\"]}, {\"executable\": \"gradle\", \"args\": [\"test\"]}], \"timeout\": 1200}"
        },
        returnExamples = {
            "{\"status\": \"started\", \"taskId\": \"bg-3\", \"pid\": 12345, \"command\": \"npm run dev\", \"cwd\": \"/abs/project\", \"requestedTimeoutSeconds\": 0, \"effectiveTimeoutSeconds\": 600}",
            "{\"status\": \"started\", \"taskId\": \"bg-4\", \"pid\": 12351, \"command\": \"gradle build\", \"cwd\": \"/abs/project\", \"requestedTimeoutSeconds\": 1200, \"effectiveTimeoutSeconds\": 600}",
            "{\"status\": \"started\", \"taskId\": \"bg-5\", \"pid\": 12387, \"command\": \"gradle test --continuous\", \"cwd\": \"/abs/project\", \"requestedTimeoutSeconds\": 1800, \"effectiveTimeoutSeconds\": 600}",
            "{\"status\": \"started\", \"taskId\": \"bg-6\", \"pid\": 12402, \"command\": \"python -m http.server 8000\", \"cwd\": \"/abs/project\", \"requestedTimeoutSeconds\": 3600, \"effectiveTimeoutSeconds\": 600}",
            "Invalid arguments: exactly one command is required (background mode does not chain); got 2."
        })
public final class RunTaskTool implements PreparedTool<RunTaskTool.Args> {
    private final ProcessExecutionCapability capability;

    /** Declaration-only instance; the host supplies the capability at execution time. */
    public RunTaskTool() {
        this.capability = null;
    }

    /** Creates an instance bound to the given host capability. */
    public RunTaskTool(@NonNull ProcessExecutionCapability capability) {
        this.capability = capability;
    }

    /** Model-facing arguments of {@code run_task}. */
    public record Args(
            @SecurityHint(ParamCategory.SHELL_COMMAND)
                    @Doc("Exactly one command: {executable, args}. Background mode does not chain.")
                    @NonNull List<RunCommandTool.@NonNull CommandInput> commands,
            @Doc(
                            "Request network access for this task. Defaults to false; true may require approval.")
                    Boolean network,
            @NonNull
                    @Doc(
                            "Requested max lifetime in seconds. 0 selects the configured maximum; larger values are capped by that maximum.")
                    Integer timeout) {}

    @Override
    public @NonNull String getName() {
        return "run_task";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull ToolPreparation prepare(
            @NonNull Args args, PluginHost.@NonNull Invocation invocation) {
        if (args.commands().size() != 1)
            throw new IllegalArgumentException("Exactly one background command is required");
        return new ToolPreparation(
                new ToolPreparation.ProcessIntent(
                        args.commands().stream()
                                .map(command -> new Command(command.executable(), command.args()))
                                .toList(),
                        ChainMode.STOP_ON_FAILURE,
                        Boolean.TRUE.equals(args.network()),
                        Duration.ofSeconds(args.timeout())),
                new JsonValue.ObjectValue(Map.of()));
    }

    @Override
    public @NonNull String execute(@NonNull Args args) {
        return execute(args, processExecutionCapability());
    }

    /** Returns the host-supplied capability; throws if none was injected. */
    public @NonNull ProcessExecutionCapability processExecutionCapability() {
        if (capability == null) throw new SecurityException("Host must supply tool capability");
        return capability;
    }

    /** Runs the tool against the supplied capability. */
    public @NonNull String execute(
            @NonNull Args args, @NonNull ProcessExecutionCapability capability) {
        int timeout = args.timeout();
        if (timeout < 0)
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Invalid arguments: timeout must be zero or positive.");
        if (args.commands().size() != 1)
            return ToolErrors.failure(
                    ToolErrorCode.VALIDATION.INVALID_ARGUMENTS,
                    "Invalid arguments: exactly one command is required (background mode does not"
                            + " chain); got "
                            + args.commands().size()
                            + ".");
        var input = args.commands().getFirst();
        long maximumTimeout = capability.maxRuntime().toSeconds();
        var info =
                capability.start(
                        new Command(input.executable(), input.args()),
                        timeout,
                        Boolean.TRUE.equals(args.network()));
        try {
            return startedResult(info, timeout, maximumTimeout);
        } catch (RuntimeException e) {
            capability.cancel(info.taskId());
            return ToolErrors.failure(
                    ToolErrorCode.RESULT.ENCODING_FAILED,
                    "Encoding failed: the task result could not be encoded; the started task was"
                            + " stopped (taskId="
                            + info.taskId()
                            + "): "
                            + e.getMessage());
        }
    }

    private static @NonNull String startedResult(
            @NonNull TaskInfo info, int timeout, long maximumTimeout) {
        return ToolJson.object(
                new Result(
                        "started",
                        "If the assignment needs the final result, call view_task once with this taskId and waitForExit=true."
                                + " Do not finish the assignment with a waiting message or poll."
                                + " If only starting a long-lived service was requested, report that it started without waiting for exit."
                                + " Answer in the user's language.",
                        info.taskId(),
                        info.pid(),
                        info.command(),
                        info.cwd(),
                        timeout,
                        timeout == 0 ? maximumTimeout : Math.min(timeout, maximumTimeout)));
    }

    /** JSON result payload of {@code run_task}. */
    public record Result(
            @NonNull String status,
            @NonNull String nextStep,
            @NonNull String taskId,
            long pid,
            @NonNull String command,
            @NonNull String cwd,
            int requestedTimeoutSeconds,
            long effectiveTimeoutSeconds) {}
}
