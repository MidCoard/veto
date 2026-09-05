package top.focess.veto.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.builtin.RunCommandTool;
import top.focess.veto.agent.tool.builtin.RunTaskTool;
import top.focess.veto.llm.config.LlmJacksonConfig;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.sandbox.ChainMode;
import top.focess.veto.sandbox.Command;
import top.focess.veto.sandbox.CommandResult;
import top.focess.veto.sandbox.SandboxManager;
import top.focess.veto.sandbox.SandboxProfile;

@Component
public final class ProcessExecutionCapabilityImpl implements ProcessExecutionCapability {
    private final @NonNull SandboxManager sandboxManager;
    private final @NonNull BackgroundTaskManager taskManager;
    private final @NonNull ObjectMapper mapper;

    public ProcessExecutionCapabilityImpl(
            @NonNull SandboxManager sandboxManager,
            @NonNull BackgroundTaskManager taskManager,
            @Qualifier(LlmJacksonConfig.LLM_OBJECT_MAPPER) @NonNull ObjectMapper mapper) {
        this.sandboxManager = sandboxManager;
        this.taskManager = taskManager;
        this.mapper = mapper;
    }

    @Override
    public @NonNull String runCommand(RunCommandTool.@NonNull Args args) {
        var authorized =
                CapabilityAccess.require(ToolCapability.PROCESS_EXECUTION, "run_command", args);
        if (args.timeout() < 0) return ToolErrors.failure("timeout must be zero or positive");
        if (args.commands().isEmpty())
            return ToolErrors.failure("commands must contain at least one command");
        ToolExecutionPermit permit = authorized.executionPermit();
        String callId = permit.callId();
        Path workspaceRoot = permit.requireExecutionRoot();
        SandboxProfile profile =
                SandboxProfile.forExecution(
                        workspaceRoot,
                        permit.protectedPaths(),
                        Boolean.TRUE.equals(args.network()));
        List<Command> commands =
                args.commands().stream()
                        .map(command -> new Command(command.executable(), command.args()))
                        .toList();
        ChainMode connect = args.connect();
        if (connect == null) connect = ChainMode.STOP_ON_FAILURE;
        Duration timeout = args.timeout() == 0 ? Duration.ZERO : Duration.ofSeconds(args.timeout());
        String sandboxId = "runcmd-" + callId;
        var handle = sandboxManager.provision(sandboxId, profile);
        try {
            CommandResult result =
                    sandboxManager
                            .substrate()
                            .runCommands(handle, commands, workspaceRoot, connect, timeout);
            String stderr = result.stderr();
            String content = result.stdout() + (stderr.isEmpty() ? "" : "\n[stderr]\n" + stderr);
            if (!result.success()) {
                return ToolErrors.failure(
                        "COMMAND_FAILED", content + "\n(exit code: " + result.exitCode() + ")");
            }
            return content;
        } finally {
            sandboxManager.deprovision(sandboxId);
        }
    }

    @Override
    public @NonNull String runTask(RunTaskTool.@NonNull Args args) {
        var authorized =
                CapabilityAccess.require(ToolCapability.PROCESS_EXECUTION, "run_task", args);
        int timeout = args.timeout();
        if (timeout < 0) {
            return ToolErrors.failure("run_task timeout must be zero or positive.");
        }
        if (args.commands().size() != 1) {
            return ToolErrors.failure(
                    "run_task requires exactly one command (background mode does not chain); got "
                            + args.commands().size());
        }
        RunCommandTool.CommandInput input = args.commands().get(0);
        var ctx = authorized;
        String agentId = ctx.agentId();
        UUID sessionId = ctx.sessionId();
        var cwd = ctx.executionPermit().requireExecutionRoot();
        SandboxProfile profile =
                SandboxProfile.forExecution(
                        cwd,
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
            return ToolErrors.failure(
                    "Task response encoding failed; the started task was stopped (taskId="
                            + info.taskId()
                            + "): "
                            + e.getMessage());
        }
    }
}
