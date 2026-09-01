package top.focess.veto.agent.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.mcp.Doc;
import top.focess.veto.agent.mcp.NativeTool;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.SecurityHint;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolCapability;
import top.focess.veto.agent.mcp.ToolDoc;
import top.focess.veto.agent.mcp.ToolDocs;
import top.focess.veto.agent.mcp.ToolErrors;
import top.focess.veto.agent.mcp.ToolResultFormat;
import top.focess.veto.agent.mcp.ToolSecurity;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.sandbox.Command;
import top.focess.veto.sandbox.SandboxProfile;

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
@ToolSecurity(risk = RiskCategory.SHELL_EXEC, capability = ToolCapability.PROCESS_EXECUTION)
public final class RunTaskTool implements NativeTool<RunTaskTool.Args> {

    private final @NonNull BackgroundTaskManager taskManager;
    private final @NonNull ObjectMapper mapper;

    public RunTaskTool(
            @NonNull BackgroundTaskManager taskManager,
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NonNull ObjectMapper mapper) {
        this.taskManager = taskManager;
        this.mapper = mapper;
    }

    @ToolDoc(
            resultFormats = {ToolResultFormat.JSON},
            description =
                    "Launch a long-running command as a detached background task (non-blocking). "
                            + "Returns a taskId immediately; the process keeps running across turns.",
            usage =
                    """
                    #### When to use
                    Use `run_task` for a long-running process that you want running while you keep \
                    working - a dev server (`npm run dev`), a file watcher, \
                    a long build you will check later. The call returns at once with a `taskId`; \
                    the process survives across turns and its output is captured for you.

                    #### When NOT to use
                    - Do not use it for a command whose result you need inline in this turn - use \
                    `run_command` (blocking) instead.
                    - Do not launch more than one command per call - background mode does not \
                    chain; express a pipeline as separate steps.
                    - Do not stop a task you launched with an OS kill command (`taskkill` / `kill`) \
                    - use `stop_task` with its `taskId`. That is the only sanctioned stop path and \
                    it keeps the task registry consistent.

                    #### Behavior
                    Starts the single `commands[0]` entry through the sandbox substrate and returns \
                    immediately. Ordinary executables use direct argv execution; Windows `.cmd`/`.bat` \
                    launchers use the restricted `ComSpec` bridge. Output (stdout+stderr merged) is \
                    drained into a 5000-line ring buffer you can read via \
                    `view_task`. `timeout` (seconds; 0 = sandbox-profile maximum) bounds the task's total \
                    lifetime - it is auto-killed after it elapses. When the task ends you are told \
                    about it on your next turn; you can also inspect it any time with `view_task` \
                    or end it with `stop_task`.

                    #### Return format
                    A JSON outcome: `{"status":"started","taskId":"bg-3","pid":1234, \
                    "command":"npm run dev","cwd":"...","requestedTimeoutSeconds":0, \
                    "effectiveTimeoutSeconds":600}`.

                    #### Errors & edge cases
                    - Any supplied command count other than one -> rejected (background mode does not chain).
                    - A negative supplied `timeout` -> rejected.
                    - Only the latest 5000 output lines are retained; an unterminated line is capped at 65536 bytes.
                    - `cwd` outside an allowed root -> the Gateway blocks the call.

                    #### Security
                    Same screening as `run_command`: `commands` carries SHELL_COMMAND and `cwd` \
                    carries FILESYSTEM_PATH; `RiskCategory.SHELL_EXEC`, always audited and may \
                    require human approval. Ordinary executables are direct argv launches; Windows \
                    `.cmd`/`.bat` shims use the restricted `ComSpec` bridge and reject interpreter \
                    metacharacters. Prefer the narrowest `cwd`.
                    """,
            examples = {
                "{\"commands\": [{\"executable\": \"npm\", \"args\": [\"run\", \"dev\"]}], \"cwd\": \"/abs/app\", \"timeout\": 0}",
                "{\"commands\": [{\"executable\": \"python\", \"args\": [\"-m\", \"http.server\", \"8000\"]}], \"cwd\": \"/abs/site\", \"timeout\": 3600}",
                "{\"commands\": [{\"executable\": \"gradle\", \"args\": [\"test\", \"--continuous\"]}], \"cwd\": \"/abs\", \"timeout\": 1800}"
            },
            returnExamples = {
                "{\"status\": \"started\", \"taskId\": \"bg-3\", \"pid\": 12345, \"command\": \"npm run dev\","
                        + " \"cwd\": \"/abs/app\", \"requestedTimeoutSeconds\": 0, \"effectiveTimeoutSeconds\": 600}"
            })
    public record Args(
            @SecurityHint(ParamCategory.SHELL_COMMAND)
                    @Doc("Exactly one command: {executable, args}. Background mode does not chain.")
                    @NonNull List<RunCommandTool.CommandInput> commands,
            @SecurityHint(ParamCategory.FILESYSTEM_PATH)
                    @Doc("Working directory; must be under an allowed root (Gateway-checked).")
                    @NonNull String cwd,
            @Doc(
                            "Request network access for this task. Defaults to false; true is separately Gateway-screened.")
                    Boolean network,
            @NonNull
                    @Doc(
                            "Requested max lifetime in seconds. 0 selects the sandbox-profile"
                                    + " maximum; larger values are capped by that maximum.")
                    Integer timeout) {}

    @Override
    public @NonNull String getName() {
        return "run_task";
    }

    @Override
    public @NonNull String getDescription() {
        return "Launch a long-running command as a detached background task (non-blocking). Uses"
                + " the same {executable,args} entry shape as run_command; returns a taskId. Use for servers/watchers"
                + " (npm run dev). Manage with view_task / stop_task.";
    }

    @Override
    public @NonNull Class<Args> getArgsClass() {
        return ToolDocs.nonNullClass(Args.class);
    }

    @Override
    public @NonNull String execute(@NonNull Args args) {
        int timeout = args.timeout();
        if (timeout < 0) {
            return error("run_task timeout must be zero or positive.");
        }
        if (args.commands().size() != 1) {
            return error(
                    "run_task requires exactly one command (background mode does not chain); got "
                            + args.commands().size());
        }
        RunCommandTool.CommandInput input = args.commands().get(0);
        var ctx = ToolCallContextHolder.get();
        if (ctx == null) {
            throw new SecurityException("run_task requires its screened execution permit");
        }
        String agentId = ctx.agentId();
        java.util.UUID sessionId = ctx.sessionId();
        Path cwd = Path.of(args.cwd());
        SandboxProfile profile =
                SandboxProfile.forExecution(
                        ctx.executionPermit().sandboxRoot("cwd"),
                        ctx.executionPermit().protectedPaths(),
                        Boolean.TRUE.equals(args.network()));
        BackgroundTaskManager.TaskInfo info =
                taskManager.start(
                        agentId,
                        new Command(input.executable(), input.args()),
                        cwd,
                        timeout,
                        sessionId,
                        profile);
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("status", "started");
            envelope.put("taskId", info.taskId());
            envelope.put("pid", info.pid());
            envelope.put("command", info.command());
            envelope.put("cwd", info.cwd());
            long profileTimeoutSeconds = profile.maxWallClock().toSeconds();
            long effectiveTimeoutSeconds =
                    timeout <= 0 ? profileTimeoutSeconds : Math.min(timeout, profileTimeoutSeconds);
            envelope.put("requestedTimeoutSeconds", timeout);
            envelope.put("effectiveTimeoutSeconds", effectiveTimeoutSeconds);
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) {
            taskManager.stop(agentId, info.taskId(), BackgroundTaskManager.ExitCause.AGENT_STOP);
            return error(
                    "Task response encoding failed; the started task was stopped (taskId="
                            + info.taskId()
                            + "): "
                            + e.getMessage());
        }
    }

    private static @NonNull String error(@NonNull String message) {
        return ToolErrors.failure(message);
    }
}
