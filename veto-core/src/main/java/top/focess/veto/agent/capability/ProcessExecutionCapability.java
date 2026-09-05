package top.focess.veto.agent.capability;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.sandbox.BackgroundTaskManager;
import top.focess.veto.sandbox.ChainMode;
import top.focess.veto.sandbox.Command;
import top.focess.veto.sandbox.CommandResult;

public sealed interface ProcessExecutionCapability extends Capability
        permits ProcessExecutionCapabilityImpl {
    @NonNull CommandResult run(
            @NonNull List<Command> commands,
            @NonNull ChainMode mode,
            @NonNull Duration timeout,
            boolean network);

    BackgroundTaskManager.@NonNull TaskInfo start(
            @NonNull Command command, int timeoutSeconds, boolean network);

    @NonNull Duration maxRuntime();

    void cancel(@NonNull String taskId);
}
