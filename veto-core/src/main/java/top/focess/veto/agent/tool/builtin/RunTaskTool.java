package top.focess.veto.agent.tool.builtin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolJson;
import top.focess.veto.agent.tool.ToolResultFormat;
import top.focess.veto.agent.tool.ToolSecurity;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.sandbox.Command;

/**
 * {@code run_task} - launch a long-running command as a detached background task. Takes the same
 * command shape as {@code run_command} but does <b>not</b> block: the process is started by the
 * sandbox substrate and drained/tracked by {@link BackgroundTaskManager}, and the call returns
 * immediately with a {@code taskId}. Use this for servers/watchers/daemons that never exit (e.g.
 * {@code npm run dev}); use {@code run_command} for commands whose result you need inline.
 *
 * <p>Background = a single command (no chaining); multiple commands are rejected. {@code timeout}
 * (seconds, required; {@code 0} selects the sandbox-profile maximum) is the requested lifetime; the
 * effective lifetime never exceeds the profile maximum. Follow up with {@code view_task} (status +
 * recent output) and {@code stop_task}. When a task ends, the agent is told about it on its next
 * turn.
 */
@Component
@ToolSecurity(capability = ToolCapability.PROCESS_EXECUTION, defaultDanger = Danger.ELEVATED)
@ToolDoc(
        resultFormats = {ToolResultFormat.JSON},
        description =
                "Launch a long-running command as a detached background task (non-blocking). "
                        + "Returns a taskId immediately; the process keeps running across turns.",
        behavior =
                """
                Starts the single `commands[0]` entry and returns immediately. The executable and arguments \
                follow the same direct-execution rules as `run_command`. It runs from the session workspace \
                root. Output (stdout+stderr merged) is captured for `view_task`. \
                `timeout` (seconds; 0 selects the configured maximum) bounds the task's total \
                lifetime - it is auto-killed after it elapses. When the task ends you are told \
                about it on your next turn; you can also inspect it any time with `view_task` \
                or end it with `stop_task`.
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
                "effectiveTimeoutSeconds":600}`.
                """,
        errorsAndEdgeCases =
                """
                - Any supplied command count other than one -> rejected (background mode does not chain).
                - A negative supplied `timeout` -> rejected.
                - Only the latest 5000 output lines are retained; an unterminated line is capped at 65536 bytes.
                """,
        security =
                "The working directory is the session workspace root. Execution and requested network access may require approval. Background execution does not grant additional file or network access.",
        examples = {
            "{\"commands\": [{\"executable\": \"npm\", \"args\": [\"run\", \"dev\"]}], \"timeout\": 0}",
            "{\"commands\": [{\"executable\": \"python\", \"args\": [\"-m\", \"http.server\", \"8000\"]}], \"timeout\": 3600}",
            "{\"commands\": [{\"executable\": \"gradle\", \"args\": [\"test\", \"--continuous\"]}], \"timeout\": 1800}"
        },
        returnExamples = {
            "{\"status\": \"started\", \"taskId\": \"bg-3\", \"pid\": 12345, \"command\": \"npm run dev\","
                    + " \"cwd\": \"/abs/app\", \"requestedTimeoutSeconds\": 0, \"effectiveTimeoutSeconds\": 600}"
        })
public final class RunTaskTool implements ProcessExecutionTool<RunTaskTool.Args> {
    private final @NonNull ProcessExecutionCapability capability;

    public RunTaskTool(@NonNull ProcessExecutionCapability capability) {
        this.capability = capability;
    }

    public record Args(
            @SecurityHint(ParamCategory.SHELL_COMMAND)
                    @Doc("Exactly one command: {executable, args}. Background mode does not chain.")
                    @NonNull List<RunCommandTool.@NonNull CommandInput> commands,
            @Doc(
                            "Request network access for this task. Defaults to false; true may require approval.")
                    Boolean network,
            @NonNull
                    @Doc(
                            "Requested max lifetime in seconds. 0 selects the configured"
                                    + " maximum; larger values are capped by that maximum.")
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
    public @NonNull ProcessExecutionCapability processExecutionCapability() {
        return capability;
    }

    @Override
    public @NonNull String execute(
            @NonNull Args args, @NonNull ProcessExecutionCapability capability) {
        int timeout = args.timeout();
        if (timeout < 0) return ToolErrors.failure("run_task timeout must be zero or positive.");
        if (args.commands().size() != 1)
            return ToolErrors.failure(
                    "run_task requires exactly one command (background mode does not chain); got "
                            + args.commands().size());
        var input = args.commands().getFirst();
        long maximumTimeout = capability.maxRuntime().toSeconds();
        var info =
                capability.start(
                        new Command(input.executable(), input.args()),
                        timeout,
                        Boolean.TRUE.equals(args.network()));
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "started");
            result.put("taskId", info.taskId());
            result.put("pid", info.pid());
            result.put("command", info.command());
            result.put("cwd", info.cwd());
            result.put("requestedTimeoutSeconds", timeout);
            result.put(
                    "effectiveTimeoutSeconds",
                    timeout <= 0 ? maximumTimeout : Math.min(timeout, maximumTimeout));
            return ToolJson.object(result);
        } catch (RuntimeException e) {
            capability.cancel(info.taskId());
            return ToolErrors.failure(
                    "Task response encoding failed; the started task was stopped (taskId="
                            + info.taskId()
                            + "): "
                            + e.getMessage());
        }
    }
}
