package top.focess.veto.agent.capability;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.ToolCapability;
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

    public ProcessExecutionCapabilityImpl(
            @NonNull SandboxManager sandboxManager, @NonNull BackgroundTaskManager taskManager) {
        this.sandboxManager = sandboxManager;
        this.taskManager = taskManager;
    }

    @Override
    public @NonNull CommandResult run(
            @NonNull List<Command> commands,
            @NonNull ChainMode mode,
            @NonNull Duration timeout,
            boolean network) {
        var context = CapabilityAccess.require(ToolCapability.PROCESS_EXECUTION, "run_command");
        var permit = context.executionPermit();
        String sandboxId = "runcmd-" + permit.callId();
        var handle = sandboxManager.provision(sandboxId, profile(permit, network));
        try {
            return sandboxManager
                    .substrate()
                    .runCommands(handle, commands, permit.requireExecutionRoot(), mode, timeout);
        } finally {
            sandboxManager.deprovision(sandboxId);
        }
    }

    @Override
    public BackgroundTaskManager.@NonNull TaskInfo start(
            @NonNull Command command, int timeoutSeconds, boolean network) {
        var context = CapabilityAccess.require(ToolCapability.PROCESS_EXECUTION, "run_task");
        var permit = context.executionPermit();
        return taskManager.start(
                context.agentId(),
                command,
                permit.requireExecutionRoot(),
                timeoutSeconds,
                context.sessionId(),
                profile(permit, network),
                context.requestId());
    }

    @Override
    public @NonNull Duration maxRuntime() {
        var context = CapabilityAccess.require(ToolCapability.PROCESS_EXECUTION);
        return profile(context.executionPermit(), false).maxWallClock();
    }

    @Override
    public void cancel(@NonNull String taskId) {
        var context = CapabilityAccess.require(ToolCapability.PROCESS_EXECUTION, "run_task");
        taskManager.stop(context.agentId(), taskId, BackgroundTaskManager.ExitCause.AGENT_STOP);
    }

    private static @NonNull SandboxProfile profile(
            @NonNull ToolExecutionPermit permit, boolean network) {
        return SandboxProfile.forExecution(
                permit.requireExecutionRoot(), permit.protectedPaths(), network);
    }
}
